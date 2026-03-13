package com.blind_helmet.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.SpeechRecognizer.*;
import android.speech.RecognizerIntent;
import java.util.ArrayList;
import java.util.Locale;

/**
 * SpeechController — Reconnaissance vocale pour commander les alertes
 * 
 * EN GROS : Écoute la voix de l'utilisateur après une alerte pour qu'il puisse
 * l'ignorer ("OK"), la répéter ("répète"), ou désactiver les alertes météo ("désactiver").
 * 
 * CE QUE ÇA FAIT :
 * - S'active automatiquement après la première alerte vocale
 * - Écoute pendant 3 secondes
 * - Reconnaît les commandes françaises :
 *   * "OK", "arrête", "arrêter", "stop" → ignore l'alerte
 *   * "répète", "repete", "repeat" → répète l'alerte
 *   * "désactiver", "désactive" → désactive alertes météo 5 min
 * - Vibre au début de l'écoute
 * - Annonce "Dites OK pour ignorer" en TTS
 * - Requiert permission RECORD_AUDIO
 * 
 * USAGE : MainActivity appelle startListening() après chaque alerte
 */
public class SpeechController {
    
    private SpeechRecognizer speechRecognizer;
    private Context context;
    private SpeechCallback callback;
    private AudioFeedback audioFeedback;
    private boolean isListening = false;
    
    private static final long SPEECH_TIMEOUT_MS = 3000;  // 3 secondes max
    private static final String[] STOP_COMMANDS = {"ok", "arrête", "arrêter", "stop"};
    private static final String[] REPEAT_COMMANDS = {"répète", "repete", "repeat"};
    private static final String[] DISABLE_ALERT_COMMANDS = {
        "désactiver", "desactiver", "désactive", "desactive",
        "désactivé", "desactivé", "désactivée", "desactivee",
        "activer", "active", "activé", "activée",  // mots-clés de la même famille
        "arrêt", "arret"  // mots alternatifs
    };
    
    public interface SpeechCallback {
        void onStopAlertCommand();
        void onRepeatAlertCommand();
        void onDisableAlertCommand();  // Nouvelle commande : désactiver les alertes météo
        void onListeningStart();
        void onListeningStop();
        void onError(String error);
    }
    
    // ============================================
    // Constructor
    // ============================================
    public SpeechController(Context context, AudioFeedback audioFeedback, SpeechCallback callback) {
        this.context = context;
        this.audioFeedback = audioFeedback;
        this.callback = callback;
        
        // Vérifier si SpeechRecognizer est disponible
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            this.speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            this.speechRecognizer.setRecognitionListener(new SpeechListener());
        }
    }
    
    // ============================================
    // Start Listening for Commands
    // ============================================
    public void startListening() {
        if (speechRecognizer == null) {
            if (callback != null) {
                callback.onError("Speech recognizer not available");
            }
            return;
        }
        
        if (isListening) {
            return;  // Déjà en écoute
        }
        
        isListening = true;
        
        // Vibrer pour signifier le début de l'écoute
        if (audioFeedback != null) {
            // Courte vibration (100ms)
            audioFeedback.vibrate(new long[]{0, 100}, -1);
            // Dire à l'utilisateur - plus court et plus direct
            audioFeedback.speakAlert("Commande ?");
        }
        
        // Créer intent pour la reconnaissance vocale
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH);
        // Réduire le temps de silence pour être plus réactif (1s au lieu de 2s)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);
        // Augmenter le temps avant timeout
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);
        // Permettre les résultats partiels pour être plus réactif
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);  // Plus de résultats pour augmenter les chances
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        
        if (callback != null) {
            callback.onListeningStart();
        }
        
        speechRecognizer.startListening(intent);
        
        // Timeout de sécurité: stop l'écoute après SPEECH_TIMEOUT_MS
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            this::stopListening,
            SPEECH_TIMEOUT_MS
        );
    }
    
    // ============================================
    // Stop Listening
    // ============================================
    public void stopListening() {
        if (speechRecognizer != null && isListening) {
            isListening = false;
            speechRecognizer.stopListening();
            if (callback != null) {
                callback.onListeningStop();
            }
        }
    }
    
    // ============================================
    // Check if Command Matches
    // ============================================
    private boolean matchesCommand(String text, String[] commands) {
        String normalized = text.toLowerCase().trim();
        // Normaliser aussi les accents pour être plus permissif
        String noAccents = normalized
            .replace("é", "e")
            .replace("è", "e")
            .replace("ê", "e")
            .replace("à", "a");
            
        android.util.Log.d("SpeechController", "matchesCommand - texte: '" + text + "', normalisé: '" + normalized + "', sans accent: '" + noAccents + "'");
        
        for (String cmd : commands) {
            android.util.Log.d("SpeechController", "  Test contre commande: '" + cmd + "'");
            if (normalized.contains(cmd) || normalized.equals(cmd) || noAccents.contains(cmd) || noAccents.equals(cmd)) {
                android.util.Log.d("SpeechController", "  -> CORRESPONDANCE!");
                return true;
            }
        }
        android.util.Log.d("SpeechController", "  -> Aucune correspondance");
        return false;
    }
    
    // ============================================
    // Shutdown
    // ============================================
    public void shutdown() {
        stopListening();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
    }
    
    // ============================================
    // Speech Recognition Listener
    // ============================================
    private class SpeechListener implements RecognitionListener {
        
        @Override
        public void onReadyForSpeech(android.os.Bundle params) {
            // Prêt à écouter
        }
        
        @Override
        public void onBeginningOfSpeech() {
            // L'utilisateur commence à parler
        }
        
        @Override
        public void onRmsChanged(float rmsdB) {
            // Niveau sonore - ignorer
        }
        
        @Override
        public void onBufferReceived(byte[] buffer) {
            // Buffer audio - ignorer
        }
        
        @Override
        public void onEndOfSpeech() {
            // Fin de la parole détectée
        }
        
        @Override
        public void onError(int error) {
            isListening = false;
            String errorMessage = "Speech recognition error: " + error;
            
            if (callback != null) {
                callback.onError(errorMessage);
            }
        }
        
        @Override
        public void onResults(android.os.Bundle results) {
            isListening = false;
            
            // Récupérer les résultats
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            
            if (matches != null && !matches.isEmpty()) {
                // Debug: afficher tous les résultats reconnus
                android.util.Log.d("SpeechController", "Reconnaissance vocale - Tous les résultats: " + matches.toString());
                
                String bestMatch = matches.get(0).toLowerCase();
                android.util.Log.d("SpeechController", "Meilleur résultat: '" + bestMatch + "'");
                
                // Vérifier si c'est une commande "désactiver l'alerte"
                if (matchesCommand(bestMatch, DISABLE_ALERT_COMMANDS)) {
                    android.util.Log.d("SpeechController", "Commande DISABLE détectée!");
                    if (callback != null) {
                        callback.onDisableAlertCommand();
                    }
                }
                // Vérifier si c'est une commande "arrêter"
                else if (matchesCommand(bestMatch, STOP_COMMANDS)) {
                    android.util.Log.d("SpeechController", "Commande STOP détectée!");
                    if (callback != null) {
                        callback.onStopAlertCommand();
                    }
                } 
                // Vérifier si c'est une commande "répéter"
                else if (matchesCommand(bestMatch, REPEAT_COMMANDS)) {
                    android.util.Log.d("SpeechController", "Commande REPEAT détectée!");
                    if (callback != null) {
                        callback.onRepeatAlertCommand();
                    }
                }
                else {
                    android.util.Log.d("SpeechController", "Aucune commande reconnue");
                }
            } else {
                android.util.Log.d("SpeechController", "Aucun résultat de reconnaissance vocale");
            }
        }
        
        @Override
        public void onPartialResults(android.os.Bundle partialResults) {
            // Résultats partiels - ignorer
        }
        
        @Override
        public void onEvent(int eventType, android.os.Bundle params) {
            // Événements spéciaux - ignorer
        }
    }
}
