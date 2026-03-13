package com.blind_helmet.app;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * AudioFeedback — Retour audio (TTS français)
 * 
 * EN GROS : Transforme les détections en alertes vocales françaises.
 * Ex: "Voiture à droite", "Personne devant vous".
 * 
 * CE QUE ÇA FAIT :
 * - Synthèse vocale française (TextToSpeech Android)
 * - Traduction des classes COCO (person → "Personne", car → "Voiture", etc.)
 * - Calcul de position (gauche/droite/devant) depuis la boîte de détection
 * - Anti-spam : minimum 2s entre deux annonces
 * 
 * NOTE : La partie retour haptique (vibreurs) est gérée par la partie
 * électronique (capteurs ultrasons). Aucune vibration ici.
 * 
 * TRADUCTIONS SUPPORTÉES : Personne, Vélo, Voiture, Moto, Camion, Bus,
 * Train, Feu, Panneau stop, Chien, Chat, Cheval, Vache, Mouton, Bouteille, Tasse, Sac, Chaise, Banc
 */
public class AudioFeedback {
    
    // ============================================
    // French Class Translations
    // ============================================
    private static final java.util.Map<String, String> FRENCH_LABELS;
    static {
        FRENCH_LABELS = new java.util.HashMap<>();
        FRENCH_LABELS.put("person", "Personne");
        FRENCH_LABELS.put("bicycle", "Vélo");
        FRENCH_LABELS.put("car", "Voiture");
        FRENCH_LABELS.put("motorcycle", "Moto");
        FRENCH_LABELS.put("truck", "Camion");
        FRENCH_LABELS.put("bus", "Bus");
        FRENCH_LABELS.put("train", "Train");
        FRENCH_LABELS.put("traffic light", "Feu");
        FRENCH_LABELS.put("stop sign", "Panneau stop");
        FRENCH_LABELS.put("dog", "Chien");
        FRENCH_LABELS.put("cat", "Chat");
        FRENCH_LABELS.put("horse", "Cheval");
        FRENCH_LABELS.put("cow", "Vache");
        FRENCH_LABELS.put("sheep", "Mouton");
        FRENCH_LABELS.put("bottle", "Bouteille");
        FRENCH_LABELS.put("cup", "Tasse");
        FRENCH_LABELS.put("backpack", "Sac");
        FRENCH_LABELS.put("chair", "Chaise");
        FRENCH_LABELS.put("bench", "Banc");
    }
    
    // ============================================
    // Location Mapping (from bounding box)
    // ============================================
    private enum Position {
        DEVANT("devant vous"),
        GAUCHE("à votre gauche"),
        DROITE("à votre droite"),
        HAUT("au-dessus"),
        BAS("en dessous");
        
        private final String text;
        Position(String text) {
            this.text = text;
        }
    }
    
    // ============================================
    // Constants
    // ============================================
    private static final int DEBOUNCE_MS = 2000;  // Min 2s between announcements
    
    // ============================================
    // State
    // ============================================
    private TextToSpeech tts;
    private long lastAnnouncementTime;
    private boolean isReady;
    
    // ============================================
    // Constructor
    // ============================================
    public AudioFeedback(Context context) {
        this.lastAnnouncementTime = 0;
        this.isReady = false;
        
        // Initialize TTS
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.FRENCH);
                    isReady = true;
                }
            }
        });
    }
    
    // ============================================
    // Announce Detection with Location
    // ============================================
    public void announceDetection(String label, float confidence, float centerX, float imageWidth) {
        // Debounce check
        long now = System.currentTimeMillis();
        if (now - lastAnnouncementTime < DEBOUNCE_MS) {
            return;
        }
        lastAnnouncementTime = now;
        
        if (!isReady) {
            return;
        }
        
        // Get French label
        String frenchLabel = FRENCH_LABELS.getOrDefault(label, label);
        
        // Determine position from bbox center
        Position position = determinePosition(centerX, imageWidth);
        
        // Build announcement
        String announcement = String.format("%s %s", frenchLabel, position.text);
        
        // Speak
        speak(announcement);
    }
    
    // ============================================
    // Determine Position from Center X
    // ============================================
    private Position determinePosition(float centerX, float imageWidth) {
        float relativePos = centerX / imageWidth;
        
        if (relativePos < 0.33f) {
            return Position.GAUCHE;
        } else if (relativePos > 0.67f) {
            return Position.DROITE;
        } else {
            return Position.DEVANT;
        }
    }
    
    // ============================================
    // Text-to-Speech
    // ============================================
    private void speak(String text) {
        if (isReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }
    
    // Alerte (météo, avancée, etc.)
    public void speakAlert(String text) {
        speak(text);
    }
    
    // ============================================
    // Cleanup
    // ============================================
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
    
    public boolean isReady() {
        return isReady;
    }
}
