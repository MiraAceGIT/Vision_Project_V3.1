package com.blind_helmet.app;

import java.util.ArrayList;
import java.util.List;

/**
 * DetectionData — Container pour les détections YOLO
 * 
 * EN GROS : Simple classe de données qui stocke les détections reçues du serveur.
 * Contient la liste des objets détectés avec leur classe, confiance et position.
 * 
 * CE QUE ÇA CONTIENT :
 * - Detection : classe (ex: "car"), confiance (0-1), boîte (x1,y1,x2,y2)
 * - WeatherAlerts : alertes météo (luminosité, brouillard, etc.)
 * - getHighestConfidence() : retourne la détection la plus sûre
 * 
 * USAGE : Rempli par WebSocketManager, utilisé par MainActivity et AnnotatedImageView
 */
public class DetectionData {
    
    public static class Detection {
        public String className;
        public float confidence;
        public int x1, y1, x2, y2;  // Pixel coordinates
        public int width, height;
        
        public Detection(String className, float confidence, int x1, int y1, int x2, int y2) {
            this.className = className;
            this.confidence = confidence;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.width = x2 - x1;
            this.height = y2 - y1;
        }
    }
    
    public static class WeatherAlerts {
        public boolean low_light;        // Luminosité faible
        public boolean fog_or_blur;      // Brouillard ou flou
        public boolean very_dark;        // Très sombre (alerte urgente)
        public double brightness;        // Valeur de luminosité (0-255)
        public double contrast;          // Valeur de contraste
    }
    
    public static class PersonGroup {
        public int count;                // Nombre de personnes dans le groupe
        public int position_x;           // Position X du centre du groupe
        public String side;              // "gauche" ou "droite"
    }
    
    public static class FastMovement {
        public String type;              // "animal_moving" ou "vehicle_moving"
        public String animal;            // Nom de l'animal (si type=animal_moving)
        public String vehicle;           // Nom du véhicule (si type=vehicle_moving)
        public String direction;         // "approche" ou "s'éloigne"
        public String speed;             // "rapide"
    }
    
    public static class TrafficLight {
        public String color;             // "red", "green", "yellow"
        public int position_x;           // Position X
        public String side;              // "gauche" ou "droite"
    }
    
    public static class Obstacle {
        public String objectClass;       // Classe de l'objet
        public int position_x;           // Position X
        public String side;              // "gauche" ou "droite"
        public String size;              // "large" ou "normal"
    }
    
    public static class AdvancedAlerts {
        public List<PersonGroup> person_groups = new ArrayList<>();
        public List<FastMovement> fast_movements = new ArrayList<>();
        public List<TrafficLight> traffic_lights = new ArrayList<>();
        public List<Obstacle> obstacles_ahead = new ArrayList<>();
    }
    
    public long timestamp;
    public int frameId;
    public float inferenceTimeMs;  // Temps d'inférence YOLO en millisecondes
    public List<Detection> detections;
    public WeatherAlerts weatherAlerts;  // Alertes météo/luminosité
    public AdvancedAlerts advancedAlerts;  // Alertes avancées (groupes, mouvement, feux)
    
    public DetectionData() {
        this.detections = new ArrayList<>();
        this.weatherAlerts = null;
    }
    
    public void addDetection(String className, float confidence, int x1, int y1, int x2, int y2) {
        detections.add(new Detection(className, confidence, x1, y1, x2, y2));
    }
    
    public int getDetectionCount() {
        return detections.size();
    }
    
    public Detection getHighestConfidence() {
        if (detections.isEmpty()) return null;
        
        Detection best = detections.get(0);
        for (Detection d : detections) {
            if (d.confidence > best.confidence) {
                best = d;
            }
        }
        return best;
    }
}
