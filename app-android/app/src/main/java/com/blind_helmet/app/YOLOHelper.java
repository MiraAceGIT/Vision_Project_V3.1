package com.blind_helmet.app;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Size;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.support.common.FileUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.*;

/**
 * YOLOHelper — Moteur d'inférence YOLO v8 Nano
 * 
 * EN GROS : Charge le modèle YOLOv8 (.tflite) et fait la détection d'objets
 * directement sur le téléphone. ACTUELLEMENT DÉSACTIVÉ dans MainActivity (= null),
 * le serveur Python fait l'inférence à la place. Garde ce code pour réactiver
 * l'inférence locale sur Android si besoin.
 * 
 * CE QUE ÇA FAIT :
 * - Charge yolov8n.tflite depuis assets/ (6.5 MB)
 * - Essaie d'activer le GPU TensorFlow Lite (100-150ms)
 * - Si GPU indisponible, fallback CPU (200-300ms)
 * - Prend une image 320×320 en entrée
 * - Retourne 25200 prédictions (15 classes + confiance)
 * - Applique NMS (Non-Maximum Suppression) pour éliminer doublons
 * - Filtre par seuil de confiance (0.45 par défaut)
 * 
 * CLASSES DÉTECTÉES : person, bicycle, car, motorcycle, truck, bus, train,
 * traffic light, stop sign, dog, cat, bottle, cup, backpack, chair
 * 
 * NOTE : Pour l'activer, dans MainActivity.java remplacer
 * "yoloHelper = null" par "yoloHelper = new YOLOHelper(this)"
 */
public class YOLOHelper {
    
    // ============================================
    // Detection Result Class
    // ============================================
    public static class Detection {
        public String label;           // "person", "car", etc.
        public float confidence;       // 0.0 - 1.0
        public RectF boundingBox;      // x1, y1, x2, y2
        public int classId;            // 0-14 (15 classes)
        
        public Detection(String label, float confidence, RectF bbox, int classId) {
            this.label = label;
            this.confidence = confidence;
            this.boundingBox = bbox;
            this.classId = classId;
        }
        
        @Override
        public String toString() {
            return String.format("%s: %.2f%% [%.0f,%.0f,%.0f,%.0f]",
                    label, confidence * 100,
                    boundingBox.left, boundingBox.top,
                    boundingBox.right, boundingBox.bottom);
        }
    }
    
    // ============================================
    // Class Labels (COCO + Blind Helmet relevant)
    // ============================================
    private static final String[] CLASS_LABELS = {
            "person",      // 0  - Personne (très important pour accessibilité)
            "bicycle",     // 1  - Vélo
            "car",         // 2  - Voiture
            "motorcycle",  // 3  - Moto
            "truck",       // 4  - Camion
            "bus",         // 5  - Bus
            "train",       // 6  - Train
            "traffic light", // 7 - Feu rouge/vert (critique)
            "stop sign",   // 8  - Panneau stop
            "dog",         // 9  - Chien
            "cat",         // 10 - Chat
            "bottle",      // 11 - Bouteille
            "cup",         // 12 - Tasse
            "backpack",    // 13 - Sac
            "chair"        // 14 - Chaise
    };
    
    // ============================================
    // Constants
    // ============================================
    private static final int INPUT_SIZE = 320;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float NMS_THRESHOLD = 0.45f;
    private static final int NUM_CLASSES = 15;
    
    // ============================================
    // Model & GPU State
    // ============================================
    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private boolean isGpuEnabled;
    private long modelLoadTime;
    private long lastInferenceTime;
    private Bitmap cachedBitmap;
    private int cachedHash;
    
    // ============================================
    // Constructor
    // ============================================
    public YOLOHelper(android.content.Context context) throws IOException {
        long startTime = System.currentTimeMillis();
        
        // Load model from assets
        MappedByteBuffer modelData = loadModelFromAssets(context, "yolov8n.tflite");
        
        // Setup GPU Delegate if available
        setupGpuDelegate();
        
        // Create interpreter
        Interpreter.Options options = new Interpreter.Options();
        if (isGpuEnabled) {
            options.addDelegate(gpuDelegate);
        }
        options.setNumThreads(4);
        
        interpreter = new Interpreter(modelData, options);
        
        modelLoadTime = System.currentTimeMillis() - startTime;
    }
    
    // ============================================
    // Setup GPU Delegate (Auto-fallback to CPU)
    // ============================================
    private void setupGpuDelegate() {
        // Désactiver le GPU sur les hôtes où les classes GPU delegate sont manquantes; passer au CPU
        isGpuEnabled = false;
    }
    
    // ============================================
    // Detect Objects in Bitmap
    // ============================================
    public Detection[] detect(Bitmap bitmap, float threshold) {
        long startTime = System.currentTimeMillis();
        
        // Vérification du cache : si même bitmap, retourner immédiatement
        if (isBitmapCached(bitmap)) {
            return new Detection[0];  // Ignorer l'inférence redondante
        }
        
        // Préparer l'entrée
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer inputBuffer = bitmapToByteBuffer(resized);
        
        // Exécuter l'inférence
        Object[] inputArray = {inputBuffer};
        Map<Integer, Object> outputMap = new HashMap<>();
        float[][][] output = new float[1][25200][NUM_CLASSES + 5];
        outputMap.put(0, output);
        
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap);
        
        // Analyser la sortie
        List<Detection> detections = parseYoloOutput(output[0], threshold, bitmap.getWidth(), bitmap.getHeight());
        
        // Appliquer NMS
        Detection[] finalDetections = applyNMS(detections.toArray(new Detection[0]), NMS_THRESHOLD);
        
        lastInferenceTime = System.currentTimeMillis() - startTime;
        cachedBitmap = bitmap;
        cachedHash = bitmap.hashCode();
        
        return finalDetections;
    }
    
    // ============================================
    // Cache Check
    // ============================================
    private boolean isBitmapCached(Bitmap bitmap) {
        return cachedHash == bitmap.hashCode() && cachedBitmap != null;
    }
    
    // ============================================
    // Convert Bitmap to ByteBuffer
    // ============================================
    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.rewind();
        
        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        
        for (int pixel : intValues) {
            byteBuffer.put((byte) ((pixel >> 16) & 0xFF));  // Rouge
            byteBuffer.put((byte) ((pixel >> 8) & 0xFF));   // Vert
            byteBuffer.put((byte) (pixel & 0xFF));          // Bleu
        }
        
        byteBuffer.rewind();
        return byteBuffer;
    }
    
    // ============================================
    // Parse YOLO Output (25200 predictions)
    // ============================================
    private List<Detection> parseYoloOutput(float[][] output, float threshold, int imgWidth, int imgHeight) {
        List<Detection> detections = new ArrayList<>();
        
        for (int i = 0; i < output.length; i++) {
            float[] pred = output[i];
            
            // Format YOLO : [cx, cy, w, h, obj_conf, class_0, class_1, ..., class_14]
            float x = pred[0];
            float y = pred[1];
            float w = pred[2];
            float h = pred[3];
            float objConf = pred[4];
            
            // Trouver la meilleure classe
            int bestClass = 0;
            float bestClassConf = 0;
            for (int c = 0; c < NUM_CLASSES; c++) {
                if (pred[5 + c] > bestClassConf) {
                    bestClassConf = pred[5 + c];
                    bestClass = c;
                }
            }
            
            // Confiance totale
            float totalConf = objConf * bestClassConf;
            
            // Filtrer par seuil
            if (totalConf < threshold) {
                continue;
            }
            
            // Convertir en boîte englobante (dénormaliser à la taille de l'image)
            float x1 = (x - w / 2) * imgWidth;
            float y1 = (y - h / 2) * imgHeight;
            float x2 = (x + w / 2) * imgWidth;
            float y2 = (y + h / 2) * imgHeight;
            
            RectF bbox = new RectF(
                    Math.max(0, x1),
                    Math.max(0, y1),
                    Math.min(imgWidth, x2),
                    Math.min(imgHeight, y2)
            );
            
            detections.add(new Detection(
                    CLASS_LABELS[bestClass],
                    totalConf,
                    bbox,
                    bestClass
            ));
        }
        
        return detections;
    }
    
    // ============================================
    // Non-Maximum Suppression (NMS)
    // ============================================
    private Detection[] applyNMS(Detection[] detections, float nmsThreshold) {
        List<Detection> results = new ArrayList<>();
        
        // Trier par confiance
        Arrays.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));
        
        for (Detection detection : detections) {
            boolean shouldKeep = true;
            
            for (Detection result : results) {
                float iou = calculateIoU(detection.boundingBox, result.boundingBox);
                if (iou > nmsThreshold) {
                    shouldKeep = false;
                    break;
                }
            }
            
            if (shouldKeep) {
                results.add(detection);
            }
        }
        
        return results.toArray(new Detection[0]);
    }
    
    // ============================================
    // Calculate IoU (Intersection over Union)
    // ============================================
    private float calculateIoU(RectF box1, RectF box2) {
        float intersection = Math.max(0, Math.min(box1.right, box2.right) - Math.max(box1.left, box2.left)) *
                Math.max(0, Math.min(box1.bottom, box2.bottom) - Math.max(box1.top, box2.top));
        
        float area1 = box1.width() * box1.height();
        float area2 = box2.width() * box2.height();
        float union = area1 + area2 - intersection;
        
        return intersection / (union + 1e-6f);
    }
    
    // ============================================
    // Utility: Load Model from Assets
    // ============================================
    private MappedByteBuffer loadModelFromAssets(android.content.Context context, String modelPath) throws IOException {
        // Use TFLite support utility to handle uncompressed or compressed assets safely
        return FileUtil.loadMappedFile(context, modelPath);
    }
    
    // ============================================
    // Getters & Status
    // ============================================
    public boolean isGpuEnabled() {
        return isGpuEnabled;
    }
    
    public long getLastInferenceTime() {
        return lastInferenceTime;
    }
    
    public long getModelLoadTime() {
        return modelLoadTime;
    }
    
    public int getInputSize() {
        return INPUT_SIZE;
    }
    
    // ============================================
    // Cleanup
    // ============================================
    public void close() {
        if (interpreter != null) {
            interpreter.close();
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}
