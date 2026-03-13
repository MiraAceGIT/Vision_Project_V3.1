package com.blind_helmet.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainActivity — Chef d'orchestre de l'application
 * 
 * EN GROS : C'est le cerveau de l'app. Reçoit les frames du serveur via WebSocket,
 * les affiche, lance les détections, et déclenche les alertes vocales + vibrations.
 * 
 * CE QUE ÇA FAIT :
 * - Connexion WebSocket au serveur Python (vision_server.py)
 * - Réception des frames JPEG + détections JSON
 * - Affichage temps réel avec boîtes de détection
 * - Synthèse vocale en français (ex: "Voiture à droite")
 * - Vibrations pour les alertes
 * - Gestion des permissions (Internet, Audio, Vibration)
 * - Anti-spam vocal (cooldown 30s par classe)
 * - Redétection si l'objet a bougé de >50 pixels
 * 
 * PIPELINE :
 * 1. Recevoir frame JPEG via WebSocket
 * 2. Décoder en Bitmap
 * 3. Afficher sur l'écran (avec boîtes de détection)
 * 4. Parser les détections JSON du serveur
 * 5. Annoncer en TTS + vibrer si besoin
 * 6. Mettre à jour les statistiques (FPS, latence, etc.)
 * 
 * NOTE IMPORTANTE : YOLOHelper est désactivé (= null). Le serveur Python fait
 * l'inférence. Pour activer l'inférence locale, décommenter la ligne 59.
 */
public class MainActivity extends AppCompatActivity {
    
    // ============================================
    // Constants
    // ============================================
    private static final String ESP32_URL = "ws://10.0.2.2:8765";  // ← ÉMULATEUR: utiliser 10.0.2.2 | TÉLÉPHONE RÉEL: utiliser l'IP du PC
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // ============================================
    // UI Components
    // ============================================
    private TextView statusText;
    private TextView detectionText;
    private TextView statsText;
    private AnnotatedImageView previewImage;
    private Button connectButton;
    private Button disconnectButton;
    
    // ============================================
    // Core Components
    // ============================================
    private WebSocketManager wsManager;
    private YOLOHelper yoloHelper;
    private AudioFeedback audioFeedback;
    private android.os.Handler uiHandler;
    
    // ============================================
    // Processing State
    // ============================================
    private AtomicBoolean isUIUpdatePending = new AtomicBoolean(false);  // Éviter accumulation UI tasks
    private Bitmap previousBitmap;  // Pour recycler l'ancien bitmap à chaque frame
    
    // ============================================
    // Speech Recognition
    // ============================================
    private SpeechController speechController;
    private boolean lastAlertAnnounced = false;  // Suivre si on a annoncé une alerte
    
    // ============================================
    // Statistics
    // ============================================
    private int totalFrames = 0;
    private int detectionCount = 0;
    private long totalInferenceTime = 0;
    private long startTime = 0;
    
    // ============================================
    // TTS Deduplication Cache (anti-répétition avec redétection par mouvement)
    // ============================================
    private java.util.Map<String, Long> lastAnnouncedTime = new java.util.HashMap<>();
    private java.util.Map<String, DetectionData.Detection> lastDetectionPosition = new java.util.HashMap<>();
    private static final long ANNOUNCE_COOLDOWN_MS = 30000;  // 30s minimum entre annonces du même type
    private static final float POSITION_CHANGE_THRESHOLD = 50.0f;  // 50 pixels = redétection si la personne a bougé
    
    // ============================================
    // Weather Alerts Cache avec système de répétition
    // ============================================
    private boolean lastLowLightAlert = false;
    private boolean lastFogAlert = false;
    private boolean lastVeryDarkAlert = false;
    private long lastLowLightDisappearTime = 0;  // Quand la lumière faible a disparu
    private long lastVeryDarkDisappearTime = 0;   // Quand la très faible lumière a disparu
    private static final long RESET_COUNTER_DELAY_MS = 5000;  // 5s sans alerte = reset compteur
    
    // Alertes avancées - anti-spam
    private long lastGroupAlertTime = 0;
    private long lastMovementAlertTime = 0;
    private long lastTrafficLightAlertTime = 0;
    private long lastObstacleAlertTime = 0;
    private String lastTrafficLightColor = "";
    private long trafficLightFirstSeenTime = 0;  // Pour feu vert: détection si devant depuis 2s
    private static final long ADVANCED_ALERT_COOLDOWN_MS = 5000;  // 5s entre alertes avancées
    
    // Contrôle des alertes répétées (2x d'affilé, puis 1x/minute si 50% frames en alerte)
    private boolean alertsEnabled = true;  // Peut être désactivé par l'utilisateur
    private boolean weatherAlertsDisabled = false;  // Désactivation temporaire alertes météo (5 min)
    private long weatherAlertsDisabledUntil = 0;  // Timestamp de réactivation
    private boolean isListeningForCommand = false;  // Bloque nouvelles alertes pendant écoute vocale
    private static final long WEATHER_ALERT_DISABLE_DURATION_MS = 300000;  // 5 minutes
    private java.util.Map<String, Integer> alertCounters = new java.util.HashMap<>();  // Compte les annonces
    private java.util.Map<String, Long> lastAlertTime = new java.util.HashMap<>();  // Temps dernière alerte
    private static final long ALERT_REPEAT_INTERVAL_MS = 30000;  // 30 secondes entre répétitions
    
    // Suivi des frames en alerte sur 30 secondes glissantes (pour calcul 50%)
    private java.util.Map<String, java.util.LinkedList<Long>> alertFrameTimestamps = new java.util.HashMap<>();
    private static final long SLIDING_WINDOW_MS = 30000;  // Fenêtre de 30 secondes
    private static final float ALERT_THRESHOLD_PERCENT = 0.50f;  // 50% des frames
    
    // ============================================
    // Lifecycle: onCreate
    // ============================================
    /**
     * Initialisation de l'activité principale
     * 
     * Séquence d'initialisation :
     * 1. Création du Handler UI pour les updates
     * 2. Initialisation des composants UI
     * 3. Demande des permissions (Internet, Micro, Vibration)
     * 4. Initialisation AudioFeedback, WebSocket, SpeechController
     * 5. Démarrage du chronomètre de statistiques
     * 
     * @param savedInstanceState État sauvegardé de l'activité (peut être null)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize Handler for UI updates
        uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // Initialize UI
        initializeUI();
        
        // Request permissions
        requestPermissions();
        
        // Initialize components
        initializeComponents();
        
        // Start
        startTime = System.currentTimeMillis();
    }
    
    // ============================================
    // Initialize UI Components
    // ============================================
    /**
     * Initialise les composants de l'interface utilisateur
     * 
     * Lie les vues (TextView, ImageView, Button) et configure
     * les listeners des boutons :
     * - Connexion/Déconnexion au serveur
     * - Toggle des alertes (activation/désactivation)
     */
    private void initializeUI() {
        statusText = findViewById(R.id.status_text);
        detectionText = findViewById(R.id.detection_text);
        statsText = findViewById(R.id.stats_text);
        previewImage = findViewById(R.id.preview_image);
        connectButton = findViewById(R.id.btn_connect);
        disconnectButton = findViewById(R.id.btn_disconnect);
        
        connectButton.setOnClickListener(v -> connectToESP32());
        disconnectButton.setOnClickListener(v -> disconnectFromESP32());
        
        // Bouton STOP gros pour désactiver/réactiver alertes
        Button alertToggleButton = findViewById(R.id.btn_alert_toggle);
        alertToggleButton.setOnClickListener(v -> {
            toggleAlerts();
            updateAlertButtonUI(alertToggleButton);
        });
        
        updateStatusUI("Prêt à connecter", false);
    }
    
    // Mise à jour du texte du bouton alerte selon état
    private void updateAlertButtonUI(Button button) {
        if (alertsEnabled) {
            button.setText("🔔 ALERTES ACTIVES\nCliquez pour désactiver");
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#2196F3")));  // Bleu
        } else {
            button.setText("🔕 ALERTES DÉSACTIVÉES\nCliquez pour activer");
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF6F00")));  // Orange
        }
    }
    
    // ============================================
    // Request Permissions
    // ============================================
    /**
     * Demande les permissions nécessaires à l'application
     * 
     * Permissions requises :
     * - INTERNET : connexion WebSocket
     * - VIBRATE : vibrations d'alerte
     * - RECORD_AUDIO : reconnaissance vocale
     * - ACCESS_NETWORK_STATE : vérification connexion
     * 
     * Demande uniquement si non déjà accordées (Android M+).
     */
    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_NETWORK_STATE
        };
        
        boolean needsPermission = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needsPermission = true;
                break;
            }
        }
        
        if (needsPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }
    
    // ============================================
    // Initialize Components
    // ============================================
    
    /**
     * Initialise les composants principaux de l'application
     * 
     * Composants créés :
     * - AudioFeedback : synthèse vocale + vibrations
     * - SpeechController : reconnaissance vocale (commandes "OK", "répète")
     * - WebSocketManager : réception frames du serveur Python
     * 
     * Note : YOLOHelper désactivé (inférence côté serveur).
     */
    private void initializeComponents() {
        // YOLO Helper
        // NOTE: L'inférence est effectuée côté serveur Python dans cette version.
        // Pour éviter de bloquer le démarrage (chargement du modèle TFLite sur le thread UI),
        // on désactive l'initialisation locale. Si besoin d'activer l'inférence locale plus tard,
        // on pourra initialiser YOLOHelper sur un thread de fond avec un indicateur visuel.
        yoloHelper = null;
        
        // Audio Feedback
        audioFeedback = new AudioFeedback(this);
        
        // Speech Recognition Controller
        speechController = new SpeechController(this, audioFeedback, new SpeechController.SpeechCallback() {
            @Override
            public void onStopAlertCommand() {
                // L'utilisateur a dit "OK" ou "arrête"
                audioFeedback.speakAlert("Alerte ignorée");
                lastAlertAnnounced = false;
                isListeningForCommand = false;  // Débloquer les alertes
            }
            
            @Override
            public void onRepeatAlertCommand() {
                // L'utilisateur a dit "répète"
                if (lastAlertAnnounced) {
                    audioFeedback.speakAlert("Répétition de l'alerte");
                }
                isListeningForCommand = false;  // Débloquer les alertes
            }
            
            @Override
            public void onDisableAlertCommand() {
                // L'utilisateur a dit "désactiver"
                weatherAlertsDisabled = true;
                weatherAlertsDisabledUntil = System.currentTimeMillis() + WEATHER_ALERT_DISABLE_DURATION_MS;
                audioFeedback.speakAlert("Alertes météo désactivées pour 5 minutes");
                lastAlertAnnounced = false;
                isListeningForCommand = false;  // Débloquer les alertes
                
                // Réinitialiser les compteurs
                alertCounters.clear();
                lastAlertTime.clear();
                alertFrameTimestamps.clear();
                lastLowLightAlert = false;
                lastFogAlert = false;
                lastVeryDarkAlert = false;
            }
            
            @Override
            public void onListeningStart() {
                // Écoute démarrée
            }
            
            @Override
            public void onListeningStop() {
                // Écoute arrêtée
                isListeningForCommand = false;  // Débloquer même si aucune commande détectée
            }
            
            @Override
            public void onError(String error) {
                // Erreur de reconnaissance vocale - continuer silencieusement
                isListeningForCommand = false;  // Débloquer les alertes
            }
        });
        
        // WebSocket Manager
        wsManager = new WebSocketManager(ESP32_URL, new WebSocketManager.WebSocketCallback() {
            @Override
            public void onConnected() {
                updateStatusUI("✓ Connecté au serveur", true);
            }
            
            @Override
            public void onDisconnected() {
                updateStatusUI("Déconnecté", false);
            }
            
            @Override
            public void onFrameReceived(WebSocketManager.FrameData frame) {
                // Éviter l'accumulation de tâches UI - ignorer si une update est déjà en attente
                if (isUIUpdatePending.getAndSet(true)) {
                    return;  // Ignorer cette frame, une update est déjà en cours
                }
                
                // Update preview (on UI thread) avec Handler
                uiHandler.post(() -> {
                    try {
                        // Décoder avec options pour limiter la mémoire
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 1;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;  // Utilise 50% moins de mémoire
                        
                        Bitmap bitmap = BitmapFactory.decodeByteArray(frame.jpegData, 0, frame.jpegData.length, options);
                        
                        if (bitmap != null) {
                            // Sauvegarder l'ancien bitmap pour le recycler
                            Bitmap oldBitmap = previousBitmap;
                            previousBitmap = bitmap;
                            
                            // Mettre à jour l'ImageView
                            previewImage.setImageBitmap(bitmap);
                            
                            // Afficher les détections
                            if (frame.detectionData != null && !frame.detectionData.detections.isEmpty()) {
                                previewImage.setDetections(frame.detectionData);
                                
                                // Traiter les détections (TTS, affichage texte)
                                handleDetections(frame.detectionData, bitmap.getWidth());
                                
                                // Accumuler le temps d'inférence pour les statistiques
                                if (frame.detectionData.inferenceTimeMs > 0) {
                                    totalInferenceTime += frame.detectionData.inferenceTimeMs;
                                }
                            }
                            
                            // Recycler l'ancien bitmap
                            if (oldBitmap != null && !oldBitmap.isRecycled()) {
                                oldBitmap.recycle();
                            }
                            
                            // Garbage collection périodique + mise à jour des stats
                            totalFrames++;
                            
                            // Mettre à jour les statistiques à chaque frame
                            if (frame.detectionData != null) {
                                updateStatistics((long)frame.detectionData.inferenceTimeMs);
                            }
                            
                            // Garbage collection toutes les 30 frames
                            if (totalFrames % 30 == 0) {
                                System.gc();
                            }
                        }
                    } catch (OutOfMemoryError | Exception e) {
                        e.printStackTrace();
                        System.gc();
                    } finally {
                        // Permettre la prochaine update
                        isUIUpdatePending.set(false);
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                updateStatusUI("❌ Erreur: " + error, false);
            }
        });
    }
    
    // ============================================
    // Connect to ESP32
    // ============================================
    private void connectToESP32() {
        updateStatusUI("🔄 Connexion en cours...", false);
        wsManager.connect();
    }
    
    // ============================================
    // Disconnect from ESP32
    // ============================================
    private void disconnectFromESP32() {
        wsManager.disconnect();
        updateStatusUI("Déconnecté", false);
    }
    
    // ============================================
    // Handle Detections: TTS + UI Update (avec déduplication)
    // ============================================
    private void handleDetections(DetectionData detections, int imageWidth) {
        if (detections == null) {
            return;
        }
        
        // Traiter les alertes météo si présentes
        if (detections.weatherAlerts != null) {
            handleWeatherAlerts(detections.weatherAlerts);
        }
        
        // Traiter les alertes avancées (groupes, mouvements, feux)
        if (detections.advancedAlerts != null) {
            handleAdvancedAlerts(detections.advancedAlerts);
        }
        
        // Traiter les détections d'objets (avec déduplication + redétection par mouvement)
        if (detections.getDetectionCount() > 0) {
            detectionCount++;
            
            // Récupérer la meilleure détection
            DetectionData.Detection best = detections.getHighestConfidence();
            if (best != null && audioFeedback != null) {
                long now = System.currentTimeMillis();
                Long lastTime = lastAnnouncedTime.get(best.className);
                DetectionData.Detection lastPos = lastDetectionPosition.get(best.className);
                
                // Vérifier 2 conditions pour annoncer:
                // 1. Pas d'annonce dans les 30s OU
                // 2. La personne s'est déplacée de >50px (changement de contexte)
                boolean enoughTimePassed = (lastTime == null || (now - lastTime) > ANNOUNCE_COOLDOWN_MS);
                boolean significantMovement = false;
                
                if (lastPos != null) {
                    float centerX = (best.x1 + best.x2) / 2.0f;
                    float lastCenterX = (lastPos.x1 + lastPos.x2) / 2.0f;
                    float centerY = (best.y1 + best.y2) / 2.0f;
                    float lastCenterY = (lastPos.y1 + lastPos.y2) / 2.0f;
                    
                    float distance = (float) Math.sqrt(
                        (centerX - lastCenterX) * (centerX - lastCenterX) +
                        (centerY - lastCenterY) * (centerY - lastCenterY)
                    );
                    significantMovement = (distance > POSITION_CHANGE_THRESHOLD);
                }
                
                if (enoughTimePassed || significantMovement) {
                    // Announce via TTS
                    audioFeedback.announceDetection(
                            best.className,
                            best.confidence,
                            (best.x1 + best.x2) / 2.0f,  // Center X
                            imageWidth
                    );
                    // Mettre à jour les caches
                    lastAnnouncedTime.put(best.className, now);
                    lastDetectionPosition.put(best.className, best);
                }
            }
            
            // Mettre à jour l'affichage texte des détections
            StringBuilder detectionStr = new StringBuilder();
            detectionStr.append(String.format("Détections (%d):\n", detections.getDetectionCount()));
            for (DetectionData.Detection det : detections.detections) {
                detectionStr.append(String.format("  • %s: %.1f%%\n", det.className, det.confidence * 100));
            }
            detectionText.setText(detectionStr.toString());
        }
    }
    
    // ============================================
    // Handle Weather Alerts (luminosité, brouillard) - Système répétition: 2x puis 1x/min
    // ============================================
    private void handleWeatherAlerts(DetectionData.WeatherAlerts alerts) {
        if (alerts == null || audioFeedback == null || !alertsEnabled) {
            return;
        }
        
        long now = System.currentTimeMillis();
        
        // Vérifier si les alertes météo sont temporairement désactivées
        if (weatherAlertsDisabled) {
            if (now >= weatherAlertsDisabledUntil) {
                // Réactiver après 5 minutes
                weatherAlertsDisabled = false;
                weatherAlertsDisabledUntil = 0;
            } else {
                return;  // Alertes désactivées, ignorer
            }
        }
        
        // PROGRESSION: very_dark a priorité sur low_light
        // Logique: Si very_dark est détecté, on ignore low_light (progression automatique)
        
        // ===== TRÈS SOMBRE (URGENTE) =====
        if (alerts.very_dark) {
            lastVeryDarkDisappearTime = 0;  // Reset timer
            
            if (!lastVeryDarkAlert) {
                // Première détection
                announceAlertWithRepeat("very_dark", "Attention! Lumière très faible, conditions très sombres", now);
                lastVeryDarkAlert = true;
            } else {
                // Alerte déjà en cours - vérifier si on doit répéter
                checkAndRepeatAlert("very_dark", "Attention! Lumière très faible", now);
            }
        } else {
            // very_dark a disparu
            if (lastVeryDarkAlert) {
                long timeSinceLast = now - lastVeryDarkDisappearTime;
                if (lastVeryDarkDisappearTime == 0) {
                    lastVeryDarkDisappearTime = now;  // Marquer le moment où c'est disparu
                } else if (timeSinceLast > RESET_COUNTER_DELAY_MS) {
                    // 5s sans very_dark = réinitialiser
                    lastVeryDarkAlert = false;
                    alertCounters.remove("very_dark");
                    lastAlertTime.remove("very_dark");
                    alertFrameTimestamps.remove("very_dark");
                    lastVeryDarkDisappearTime = 0;
                }
            }
        }
        
        // ===== LUMIÈRE FAIBLE (sauf si very_dark) =====
        if (alerts.low_light && !alerts.very_dark) {
            lastLowLightDisappearTime = 0;  // Reset timer
            
            if (!lastLowLightAlert) {
                // Première détection
                announceAlertWithRepeat("low_light", "Attention, lumière faible", now);
                lastLowLightAlert = true;
            } else {
                // Alerte déjà en cours
                checkAndRepeatAlert("low_light", "Attention, lumière faible", now);
            }
        } else if (!alerts.low_light || alerts.very_dark) {
            // low_light a disparu ou very_dark l'a remplacée
            if (lastLowLightAlert) {
                long timeSinceLast = now - lastLowLightDisappearTime;
                if (lastLowLightDisappearTime == 0) {
                    lastLowLightDisappearTime = now;  // Marquer le moment où c'est disparu
                } else if (timeSinceLast > RESET_COUNTER_DELAY_MS) {
                    // 5s sans low_light = réinitialiser
                    lastLowLightAlert = false;
                    alertCounters.remove("low_light");
                    lastAlertTime.remove("low_light");
                    alertFrameTimestamps.remove("low_light");
                    lastLowLightDisappearTime = 0;
                }
            }
        }
        
        // ===== BROUILLARD OU FLOU =====
        if (alerts.fog_or_blur) {
            if (!lastFogAlert) {
                announceAlertWithRepeat("fog_or_blur", "Brouillard ou flou détecté, visibilité réduite", now);
                lastFogAlert = true;
            } else {
                checkAndRepeatAlert("fog_or_blur", "Brouillard ou flou détecté", now);
            }
        } else if (alerts.fog_or_blur != (lastFogAlert)) {
            lastFogAlert = false;
            alertCounters.remove("fog_or_blur");
            lastAlertTime.remove("fog_or_blur");
            alertFrameTimestamps.remove("fog_or_blur");
        }
    }
    
    // Enregistre une frame en alerte et nettoie les anciennes (> 30s)
    private void recordAlertFrame(String alertType, long now) {
        if (!alertFrameTimestamps.containsKey(alertType)) {
            alertFrameTimestamps.put(alertType, new java.util.LinkedList<>());
        }
        
        java.util.LinkedList<Long> timestamps = alertFrameTimestamps.get(alertType);
        timestamps.add(now);
        
        // Nettoyer les timestamps > 30 secondes
        while (!timestamps.isEmpty() && (now - timestamps.getFirst()) > SLIDING_WINDOW_MS) {
            timestamps.removeFirst();
        }
    }
    
    // Calcule le % de frames en alerte sur les dernières 30 secondes
    private float getAlertPercentage(String alertType) {
        java.util.LinkedList<Long> timestamps = alertFrameTimestamps.get(alertType);
        if (timestamps == null || timestamps.isEmpty()) return 0.0f;
        
        // Estimation: 8 fps = ~240 frames/30s
        float expectedFrames = 240.0f;
        return Math.min(1.0f, timestamps.size() / expectedFrames);
    }
    
    // ============================================
    // Handle Advanced Alerts (groupes, mouvements, feux)
    // ============================================
    private void handleAdvancedAlerts(DetectionData.AdvancedAlerts alerts) {
        if (alerts == null || audioFeedback == null || !alertsEnabled) return;
        
        long now = System.currentTimeMillis();
        
        // 1. Groupes de personnes (>= 3 personnes)
        if (!alerts.person_groups.isEmpty() && now - lastGroupAlertTime > ADVANCED_ALERT_COOLDOWN_MS) {
            DetectionData.PersonGroup group = alerts.person_groups.get(0);
            String msg = "Groupe de " + group.count + " personnes à " + group.side;
            audioFeedback.speakAlert(msg);
            lastGroupAlertTime = now;
        }
        
        // 2. Mouvements rapides (animaux / véhicules)
        if (!alerts.fast_movements.isEmpty() && now - lastMovementAlertTime > ADVANCED_ALERT_COOLDOWN_MS) {
            DetectionData.FastMovement mv = alerts.fast_movements.get(0);
            String msg;
            if ("animal_moving".equals(mv.type) && mv.animal != null && !mv.animal.isEmpty()) {
                String animalFr = translateClass(mv.animal);
                msg = animalFr + " en mouvement rapide qui " + mv.direction;
            } else if ("vehicle_moving".equals(mv.type) && mv.vehicle != null && !mv.vehicle.isEmpty()) {
                String vehicleFr = translateClass(mv.vehicle);
                msg = vehicleFr + " rapide qui " + mv.direction;
            } else {
                msg = "Objet en mouvement rapide";
            }
            audioFeedback.speakAlert(msg);
            lastMovementAlertTime = now;
        }
        
        // 3. Feux piétons - uniquement le ROUGE est annoncé
        if (!alerts.traffic_lights.isEmpty()) {
            DetectionData.TrafficLight tl = alerts.traffic_lights.get(0);
            // Seul le rouge est envoyé par Python, mais on vérifie quand même
            if ("red".equals(tl.color)) {
                if (!"red".equals(lastTrafficLightColor) && now - lastTrafficLightAlertTime > ADVANCED_ALERT_COOLDOWN_MS) {
                    audioFeedback.speakAlert("Feu piéton rouge");
                    lastTrafficLightAlertTime = now;
                }
            }
            lastTrafficLightColor = tl.color;
        } else {
            // Plus de feu rouge visible
            lastTrafficLightColor = "";
            trafficLightFirstSeenTime = 0;
        }
        
        // 4. Obstacles proches (objets dans tiers inférieur de l'image)
        if (!alerts.obstacles_ahead.isEmpty() && now - lastObstacleAlertTime > ADVANCED_ALERT_COOLDOWN_MS) {
            DetectionData.Obstacle obs = alerts.obstacles_ahead.get(0);
            String objFr = translateClass(obs.objectClass);
            String sizeStr = "large".equals(obs.size) ? "grand" + (obs.objectClass.equals("person") ? "e" : "") + " " : "";
            String msg = sizeStr + objFr + " proche à " + obs.side;
            audioFeedback.speakAlert(msg);
            lastObstacleAlertTime = now;
        }
    }
    
    // Traduction d'une classe YOLO en français
    private String translateClass(String className) {
        switch (className) {
            case "person": return "Personne";
            case "car": return "Voiture";
            case "truck": return "Camion";
            case "bus": return "Bus";
            case "motorcycle": return "Moto";
            case "bicycle": return "Vélo";
            case "dog": return "Chien";
            case "cat": return "Chat";
            case "horse": return "Cheval";
            case "cow": return "Vache";
            case "sheep": return "Mouton";
            case "bench": return "Banc";
            case "chair": return "Chaise";
            default: return className;
        }
    }
    
    // Annonce alerte avec logique: 2x d'affilé, puis 1x/30s
    private void announceAlertWithRepeat(String alertType, String message, long now) {
        audioFeedback.speakAlert(message);
        alertCounters.put(alertType, 1);  // 1ère annonce
        lastAlertTime.put(alertType, now);
        lastAlertAnnounced = true;
        
        /*
        // TODO: Réactiver la détection vocale plus tard
        // Activer le micro 2 secondes après la PREMIÈRE annonce seulement
        isListeningForCommand = true;  // Bloquer nouvelles alertes
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            () -> {
                if (speechController != null && lastAlertAnnounced) {
                    speechController.startListening();
                }
                // Débloquer après 8 secondes (2s attente + 6s écoute)
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> {
                        isListeningForCommand = false;
                    },
                    6000  // 6s d'écoute pour laisser le temps
                );
            },
            2000  // Attendre 2s que le message soit fini
        );
        */
    }
    
    // Vérifie si on doit répéter l'alerte (2e fois après 3s, puis si >= 50% frames en alerte sur 30s)
    // Note: Le micro n'est PAS activé pour les répétitions, seulement pour la première alerte
    private void checkAndRepeatAlert(String alertType, String message, long now) {
        int count = alertCounters.getOrDefault(alertType, 0);
        Long lastTime = lastAlertTime.getOrDefault(alertType, now);
        
        // Enregistrer cette frame comme étant en alerte
        recordAlertFrame(alertType, now);
        
        if (count < 2 && (now - lastTime) > 3000) {
            // Annoncer 2e fois après 3 secondes minimum
            audioFeedback.speakAlert(message);
            alertCounters.put(alertType, count + 1);
            lastAlertTime.put(alertType, now);
        } else if (count >= 2 && (now - lastTime) > ALERT_REPEAT_INTERVAL_MS) {
            // Après les 2 annonces initiales: répéter si >= 50% des frames sont en alerte sur 30s
            float alertPercent = getAlertPercentage(alertType);
            if (alertPercent >= ALERT_THRESHOLD_PERCENT) {
                audioFeedback.speakAlert(message);
                lastAlertTime.put(alertType, now);
            }
        }
    }
    
    // Désactiver/réactiver les alertes
    public void toggleAlerts() {
        alertsEnabled = !alertsEnabled;
        if (!alertsEnabled) {
            // Réinitialiser les compteurs
            alertCounters.clear();
            lastAlertTime.clear();
            alertFrameTimestamps.clear();
            weatherAlertsDisabled = false;
            weatherAlertsDisabledUntil = 0;
        }
    }
    
    // ============================================
    // Process Frame: Use detections from server
    // ============================================
    // ============================================
    // Update Statistics Display
    // ============================================
    private void updateStatistics(long lastInferenceTime) {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        float fps = (float) totalFrames / (uptime > 0 ? uptime : 1);
        float avgInference = totalInferenceTime / (float) totalFrames;
        
        String stats = String.format(
                "📊 Frames: %d | FPS: %.1f | Inférence: %.0f ms | Détections: %d\n" +
                "⏱️ Latence dernière: %d ms\n" +
                "🎯 GPU: %s | Connexions: %d",
                totalFrames,
                fps,
                avgInference,
                detectionCount,
                lastInferenceTime,
                (yoloHelper != null && yoloHelper.isGpuEnabled()) ? "✓" : "✗",
                wsManager.isConnected() ? 1 : 0
        );
        
        runOnUiThread(() -> statsText.setText(stats));
    }
    
    // ============================================
    // Update Status UI
    // ============================================
    private void updateStatusUI(String message, boolean connected) {
        runOnUiThread(() -> {
            statusText.setText(message);
            connectButton.setEnabled(!connected);
            disconnectButton.setEnabled(connected);
        });
    }
    
    // ============================================
    // Permission Callback
    // ============================================
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                updateStatusUI("❌ Permissions refusées", false);
            }
        }
    }
    
    // ============================================
    // Lifecycle: onDestroy
    // ============================================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        wsManager.disconnect();
        if (yoloHelper != null) {
            yoloHelper.close();
        }
        audioFeedback.shutdown();
        if (speechController != null) {
            speechController.shutdown();
        }
    }
}
