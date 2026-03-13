package com.blind_helmet.app;

import android.util.Base64;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.*;

/**
 * WebSocketManager — Réception des frames et reconnexion automatique
 * 
 * EN GROS : Gère la connexion WebSocket avec le serveur Python. Reçoit les frames
 * JPEG + détections JSON, les met en file d'attente, et gère les reconnexions.
 * 
 * CE QUE ÇA FAIT :
 * - Connexion WebSocket au serveur (vision_server.py)
 * - Réception des messages JSON type "frame"
 * - Décodage Base64 des images JPEG
 * - Parsing des détections JSON (classe, confiance, boîtes)
 * - File d'attente des frames (max 3, FIFO pour éviter lag)
 * - Throttling : ignore les frames si <111ms depuis la dernière (max 9 FPS)
 * - Reconnexion auto avec backoff exponentiel (2s → 4s → 8s → max 30s)
 * - Ping WebSocket toutes les 30s pour maintenir la connexion
 * 
 * MESSAGES SUPPORTÉS :
 * - "frame" : image + détections + alertes météo
 * - "status" : infos serveur
 * - "ping" : keep-alive
 */
public class WebSocketManager extends WebSocketListener {
    
    // ============================================
    // Frame Data Class
    // ============================================
    public static class FrameData {
        public byte[] jpegData;
        public long timestamp;
        public int frameSize;
        public DetectionData detectionData;
        
        public FrameData(byte[] jpeg, long ts, int size) {
            this.jpegData = jpeg;
            this.timestamp = ts;
            this.frameSize = size;
            this.detectionData = new DetectionData();
        }
    }
    
    // ============================================
    // Callback Interface
    // ============================================
    public interface WebSocketCallback {
        void onConnected();
        void onDisconnected();
        void onFrameReceived(FrameData frame);
        void onError(String error);
    }
    
    // ============================================
    // Constants
    // ============================================
    private static final int RECONNECT_INITIAL_MS = 2000;
    private static final int RECONNECT_MAX_MS = 30000;
    
    // ============================================
    // State
    // ============================================
    private String serverUrl;
    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private WebSocketCallback callback;
    private volatile boolean isConnected;
    private int reconnectAttempts;
    private long reconnectDelayMs;
    private ExecutorService executorService;
    private long lastFrameReceivedTime = 0;
    private static final long MIN_FRAME_INTERVAL_MS = 111;  // Max 9 FPS pour ultra-fluidité
    
    // ============================================
    // Constructor
    // ============================================
    public WebSocketManager(String url, WebSocketCallback callback) {
        this.serverUrl = url;
        this.callback = callback;
        this.isConnected = false;
        this.reconnectAttempts = 0;
        this.reconnectDelayMs = RECONNECT_INITIAL_MS;
        this.executorService = Executors.newSingleThreadExecutor();
        
        // Setup OkHttp client
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)  // Pas de timeout pour le flux continu
                .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)  // Ping keep-alive
                .build();
    }
    
    // ============================================
    // Connect to WebSocket Server
    // ============================================
    public void connect() {
        if (isConnected) {
            return;
        }
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .build();
        
        webSocket = httpClient.newWebSocket(request, this);
    }
    
    // ============================================
    // WebSocket Listeners
    // ============================================
    @Override
    public void onOpen(WebSocket webSocket, okhttp3.Response response) {
        isConnected = true;
        reconnectAttempts = 0;
        reconnectDelayMs = RECONNECT_INITIAL_MS;
        
        if (callback != null) {
            callback.onConnected();
        }
    }
    
    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type");
            
            if ("frame".equals(type)) {
                handleFrameMessage(json);
            }
            // autres types (status, ping) ignorés silencieusement
        } catch (JSONException e) {
            if (callback != null) {
                callback.onError("JSON parse error: " + e.getMessage());
            }
        } catch (Exception e) {
            // Catch toutes les autres exceptions pour éviter le crash
            if (callback != null) {
                callback.onError("Frame processing error: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }
    
    @Override
    public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
        isConnected = false;
        
        if (callback != null) {
            callback.onError("Connection failed: " + t.getMessage());
        }
        
        // Attempt reconnect
        attemptReconnect();
    }
    
    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        isConnected = false;
        
        if (callback != null) {
            callback.onDisconnected();
        }
        
        // Auto-reconnect
        attemptReconnect();
    }
    
    // ============================================
    // Handle Frame Message (JPEG Base64 + Detections)
    // ============================================
    private void handleFrameMessage(JSONObject json) throws JSONException {
        // Throttle: ignorer les frames qui arrivent trop vite
        long now = System.currentTimeMillis();
        if (now - lastFrameReceivedTime < MIN_FRAME_INTERVAL_MS) {
            return;  // Ignorer cette frame pour économiser la mémoire
        }
        lastFrameReceivedTime = now;
        
        try {
            // Récupérer l'image
            String imageBase64 = json.optString("image");
            if (imageBase64.isEmpty()) {
                return;
            }
            
            long timestamp = json.optLong("timestamp", System.currentTimeMillis());
            int frameId = json.optInt("frame_id", 0);
            float inferenceTimeMs = (float) json.optDouble("inference_time_ms", 0.0);
            
            // Decode Base64 (using Android API)
            byte[] jpegData = Base64.decode(imageBase64, Base64.DEFAULT);
            
            if (jpegData == null || jpegData.length == 0) {
                return;  // Base64 invalide
            }
            
            FrameData frame = new FrameData(jpegData, timestamp, jpegData.length);
            frame.detectionData.frameId = frameId;
            frame.detectionData.timestamp = timestamp;
            frame.detectionData.inferenceTimeMs = inferenceTimeMs;
        
            // Parser les détections
            if (json.has("detections")) {
                try {
                    org.json.JSONArray detectionsArray = json.getJSONArray("detections");
                    for (int i = 0; i < detectionsArray.length(); i++) {
                        org.json.JSONObject det = detectionsArray.getJSONObject(i);
                        
                        String className = det.optString("class", "unknown");
                        float confidence = (float) det.optDouble("confidence", 0.0);
                        
                        if (det.has("box")) {
                            org.json.JSONObject box = det.getJSONObject("box");
                            int x1 = box.optInt("x1", 0);
                            int y1 = box.optInt("y1", 0);
                            int x2 = box.optInt("x2", 0);
                            int y2 = box.optInt("y2", 0);
                            
                            frame.detectionData.addDetection(className, confidence, x1, y1, x2, y2);
                        }
                    }
                } catch (org.json.JSONException e) {
                    // Ignorer erreurs parsing détections
                }
            }
            
            // Parser les alertes météo (luminosité, brouillard)
            if (json.has("weather_alerts")) {
                try {
                    org.json.JSONObject weatherObj = json.getJSONObject("weather_alerts");
                    DetectionData.WeatherAlerts alerts = new DetectionData.WeatherAlerts();
                    
                    alerts.low_light = weatherObj.optBoolean("low_light", false);
                    alerts.fog_or_blur = weatherObj.optBoolean("fog_or_blur", false);
                    alerts.very_dark = weatherObj.optBoolean("very_dark", false);
                    alerts.brightness = weatherObj.optDouble("brightness", 128.0);
                    alerts.contrast = weatherObj.optDouble("contrast", 50.0);
                    
                    frame.detectionData.weatherAlerts = alerts;
                    android.util.Log.d("WEATHER", String.format("Parsed: low_light=%b very_dark=%b fog=%b brightness=%.1f", 
                        alerts.low_light, alerts.very_dark, alerts.fog_or_blur, alerts.brightness));
                } catch (org.json.JSONException e) {
                    android.util.Log.e("WEATHER", "Error parsing weather_alerts: " + e.getMessage());
                }
            } else {
                android.util.Log.d("WEATHER", "No weather_alerts in JSON");
            }
            
            // Parser les alertes avancées (groupes, mouvements, feux)
            if (json.has("advanced_alerts")) {
                try {
                    org.json.JSONObject advObj = json.getJSONObject("advanced_alerts");
                    DetectionData.AdvancedAlerts advanced = new DetectionData.AdvancedAlerts();
                    
                    // Groupes de personnes
                    if (advObj.has("person_groups")) {
                        org.json.JSONArray arr = advObj.getJSONArray("person_groups");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject g = arr.getJSONObject(i);
                            DetectionData.PersonGroup pg = new DetectionData.PersonGroup();
                            pg.count = g.optInt("count", 0);
                            pg.position_x = g.optInt("position_x", 0);
                            pg.side = g.optString("side", "");
                            advanced.person_groups.add(pg);
                        }
                    }
                    
                    // Mouvements rapides
                    if (advObj.has("fast_movements")) {
                        org.json.JSONArray arr = advObj.getJSONArray("fast_movements");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject m = arr.getJSONObject(i);
                            DetectionData.FastMovement fm = new DetectionData.FastMovement();
                            fm.type = m.optString("type", "");
                            fm.animal = m.optString("animal", "");
                            fm.vehicle = m.optString("vehicle", "");
                            fm.direction = m.optString("direction", "");
                            advanced.fast_movements.add(fm);
                        }
                    }
                    
                    // Feux de circulation
                    if (advObj.has("traffic_lights")) {
                        org.json.JSONArray arr = advObj.getJSONArray("traffic_lights");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject t = arr.getJSONObject(i);
                            DetectionData.TrafficLight tl = new DetectionData.TrafficLight();
                            tl.color = t.optString("color", "");
                            tl.position_x = t.optInt("position_x", 0);
                            tl.side = t.optString("side", "");
                            advanced.traffic_lights.add(tl);
                        }
                    }
                    
                    // Obstacles proches
                    if (advObj.has("obstacles_ahead")) {
                        org.json.JSONArray arr = advObj.getJSONArray("obstacles_ahead");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            DetectionData.Obstacle ob = new DetectionData.Obstacle();
                            ob.objectClass = o.optString("class", "");
                            ob.position_x = o.optInt("position_x", 0);
                            ob.side = o.optString("side", "");
                            ob.size = o.optString("size", "normal");
                            advanced.obstacles_ahead.add(ob);
                        }
                    }
                    
                    frame.detectionData.advancedAlerts = advanced;
                } catch (org.json.JSONException e) {
                    android.util.Log.e("ADVANCED", "Error parsing advanced_alerts: " + e.getMessage());
                }
            }
            
            // Transmettre directement la frame via callback
            if (callback != null) {
                callback.onFrameReceived(frame);
            }
        } catch (Exception e) {
            // Protection contre tout crash (Base64 invalide, OutOfMemory, etc.)
            e.printStackTrace();
        }
    }
    
    // ============================================
    // Auto-Reconnect with Exponential Backoff
    // ============================================
    private void attemptReconnect() {
        executorService.execute(() -> {
            reconnectAttempts++;
            
            try {
                Thread.sleep(reconnectDelayMs);
                
                // Doubler le délai (max 30 sec)
                reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
                
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    // ============================================
    // Getters
    // ============================================
    public boolean isConnected() {
        return isConnected;
    }
    
    // ============================================
    // Disconnect & Cleanup
    // ============================================
    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        isConnected = false;
        executorService.shutdown();
    }
}
