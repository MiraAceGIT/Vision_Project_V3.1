#!/usr/bin/env python3
"""
Vision Server — Remplace l'ESP32-CAM pour la démonstration

Capture la webcam PC, lance l'inférence YOLOv8, et diffuse les détections
via WebSocket à l'application Android.

Architecture :
    OpenCV capture → YOLOv8 inférence → WebSocket JSON

Usage :
    python vision_server.py
    
Le serveur écoute sur ws://0.0.0.0:8765
"""

import cv2
import asyncio
import websockets
import json
import base64
import time
import numpy as np
from ultralytics import YOLO
from datetime import datetime

# ============================================================================
# Configuration
# ============================================================================

CAMERA_ID = 0
YOLO_MODEL = "yolov8n.pt"
CONFIDENCE_THRESHOLD = 0.45
FRAME_WIDTH = 640
FRAME_HEIGHT = 480
FPS_TARGET = 8
JPEG_QUALITY = 75

# ============================================================================
# Initialisation
# ============================================================================

print("🚀 Démarrage Vision Server")
print(f"📷 Caméra: {CAMERA_ID}")
print(f"🔍 Modèle: {YOLO_MODEL}")
print(f"🎯 FPS cible: {FPS_TARGET}")

# Charger le modèle YOLO
print("⏳ Chargement du modèle...")
model = YOLO(YOLO_MODEL)
print("✅ Modèle chargé")

# Ouvrir la caméra
cap = cv2.VideoCapture(CAMERA_ID)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
cap.set(cv2.CAP_PROP_FPS, 30)

if not cap.isOpened():
    print("❌ Erreur: Impossible d'accéder à la caméra")
    exit(1)

print(f"✅ Caméra ouverte ({FRAME_WIDTH}x{FRAME_HEIGHT})")

# Liste des clients WebSocket connectés
clients = set()

# Historique des détections pour analyse de mouvement
previous_detections = {}

# ============================================================================
# Fonctions utilitaires
# ============================================================================

async def handle_client(websocket):
    """
    Gère la connexion d'un client WebSocket
    
    Ajoute le client à la liste des clients actifs, attend sa déconnexion,
    puis le retire automatiquement de la liste lors de la fermeture.
    
    Paramètres:
        websocket: Instance de connexion WebSocket du client
    
    Lève:
        Exception: En cas d'erreur de connexion WebSocket
    """
    clients.add(websocket)
    print(f"✅ Client connecté: {websocket.remote_address}")
    
    try:
        await websocket.wait_closed()
    finally:
        clients.remove(websocket)
        print(f"❌ Client déconnecté: {websocket.remote_address}")


async def broadcast(message):
    """
    Envoie un message à tous les clients WebSocket connectés
    
    Diffuse le message en parallèle à tous les clients actifs.
    Ignore les erreurs individuelles pour éviter qu'un client
    défaillant ne bloque les autres.
    
    Paramètres:
        message (str): Message texte à envoyer (généralement du JSON)
    
    Note:
        Utilise asyncio.gather avec return_exceptions=True pour
        la résilience aux erreurs
    """
    if clients:
        await asyncio.gather(
            *[client.send(message) for client in clients],
            return_exceptions=True
        )


def extract_detections(results):
    """
    Extrait les détections YOLO au format JSON
    
    Paramètres:
        results: Résultat de l'inférence YOLOv8
        
    Retourne:
        Liste de détections avec classe, confiance et boîte
    """
    detections = []
    
    if results[0].boxes is not None:
        for box in results[0].boxes:
            x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
            conf = float(box.conf[0])
            cls = int(box.cls[0])
            
            # Récupérer le nom COCO anglais (ex: "person", "car")
            # AudioFeedback.java fait la traduction en français
            class_name = model.names[cls]
            
            if conf >= CONFIDENCE_THRESHOLD:
                detections.append({
                    "class": class_name,
                    "confidence": round(conf, 3),
                    "box": {
                        "x1": int(x1),
                        "y1": int(y1),
                        "x2": int(x2),
                        "y2": int(y2),
                        "width": int(x2 - x1),
                        "height": int(y2 - y1),
                        "center_x": int((x1 + x2) / 2),
                        "center_y": int((y1 + y2) / 2)
                    }
                })
    
    return detections


def detect_person_groups(detections):
    """
    Détecte les groupes de personnes (>= 3 personnes proches)
    
    Retourne:
        Liste de groupes avec nombre de personnes et position
    """
    persons = [d for d in detections if d["class"] == "person"]
    if len(persons) < 3:
        return []
    
    groups = []
    visited = set()
    PROXIMITY_THRESHOLD = 150  # pixels
    
    for i, p1 in enumerate(persons):
        if i in visited:
            continue
        
        group = [p1]
        visited.add(i)
        
        for j, p2 in enumerate(persons):
            if j <= i or j in visited:
                continue
            
            # Distance entre centres
            dx = p1["box"]["center_x"] - p2["box"]["center_x"]
            dy = p1["box"]["center_y"] - p2["box"]["center_y"]
            dist = (dx**2 + dy**2) ** 0.5
            
            if dist < PROXIMITY_THRESHOLD:
                group.append(p2)
                visited.add(j)
        
        if len(group) >= 3:
            avg_x = sum(p["box"]["center_x"] for p in group) / len(group)
            groups.append({
                "count": len(group),
                "position_x": int(avg_x),
                "side": "gauche" if avg_x < FRAME_WIDTH / 2 else "droite"
            })
    
    return groups


def detect_fast_movement(current_detections, prev_detections, frame_time_delta=0.125):
    """
    Détecte les objets en mouvement rapide (animaux, véhicules)
    
    Retourne:
        Liste d'alertes de mouvement rapide
    """
    alerts = []
    SPEED_THRESHOLD = 200  # pixels par seconde
    
    animals = ["dog", "cat", "horse", "cow", "sheep"]
    vehicles = ["car", "truck", "bus", "motorcycle", "bicycle"]
    
    for curr in current_detections:
        class_name = curr["class"]
        if class_name not in animals + vehicles:
            continue
        
        # Chercher l'objet dans le frame précédent
        curr_x = curr["box"]["center_x"]
        curr_y = curr["box"]["center_y"]
        
        for prev_class, prev_pos in prev_detections.items():
            if prev_class != class_name:
                continue
            
            dx = curr_x - prev_pos[0]
            dy = curr_y - prev_pos[1]
            distance = (dx**2 + dy**2) ** 0.5
            speed = distance / frame_time_delta
            
            if speed > SPEED_THRESHOLD:
                direction = "approche" if dx > 0 else "s'éloigne"
                if class_name in animals:
                    alerts.append({
                        "type": "animal_moving",
                        "animal": class_name,
                        "direction": direction,
                        "speed": "rapide"
                    })
                elif class_name in vehicles:
                    alerts.append({
                        "type": "vehicle_moving",
                        "vehicle": class_name,
                        "direction": direction,
                        "speed": "rapide"
                    })
    
    return alerts


def detect_traffic_light_color(frame, box):
    """
    Analyse la couleur d'un feu de circulation détecté
    Méthode 1 : analyse HSV
    Méthode 2 : position du segment allumé (haut=rouge, bas=vert)
    
    Retourne:
        "red", "green", "yellow" ou "unknown"
    """
    x1, y1, x2, y2 = box["x1"], box["y1"], box["x2"], box["y2"]
    
    # Extraire la région du feu
    roi = frame[y1:y2, x1:x2]
    if roi.size == 0 or roi.shape[0] < 5 or roi.shape[1] < 5:
        return "unknown"
    
    # --- Méthode HSV (seuils permissifs) ---
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    
    # Rouge : saturation basse acceptée (50), valeur basse acceptée (80)
    red_mask1 = cv2.inRange(hsv, np.array([0, 50, 80]), np.array([15, 255, 255]))
    red_mask2 = cv2.inRange(hsv, np.array([160, 50, 80]), np.array([180, 255, 255]))
    red_pixels = cv2.countNonZero(red_mask1) + cv2.countNonZero(red_mask2)
    
    # Vert
    green_mask = cv2.inRange(hsv, np.array([35, 50, 80]), np.array([90, 255, 255]))
    green_pixels = cv2.countNonZero(green_mask)
    
    # Jaune
    yellow_mask = cv2.inRange(hsv, np.array([15, 50, 80]), np.array([35, 255, 255]))
    yellow_pixels = cv2.countNonZero(yellow_mask)
    
    total_pixels = roi.shape[0] * roi.shape[1]
    threshold = total_pixels * 0.03  # 3% (plus permissif)
    
    print(f"   🚦 Feu - rouge:{red_pixels} vert:{green_pixels} jaune:{yellow_pixels} total:{total_pixels} seuil:{int(threshold)}")
    
    if red_pixels > threshold and red_pixels >= green_pixels and red_pixels >= yellow_pixels:
        return "red"
    elif green_pixels > threshold and green_pixels > red_pixels:
        return "green"
    elif yellow_pixels > threshold:
        return "yellow"
    
    # --- Méthode de secours : position du segment allumé ---
    # Diviser en 3 zones (haut=rouge, milieu=jaune, bas=vert)
    h = roi.shape[0]
    top_zone    = roi[:h//3, :]
    middle_zone = roi[h//3:2*h//3, :]
    bottom_zone = roi[2*h//3:, :]
    
    top_brightness    = top_zone.mean()
    middle_brightness = middle_zone.mean()
    bottom_brightness = bottom_zone.mean()
    
    print(f"   🚦 Fallback - haut:{top_brightness:.1f} milieu:{middle_brightness:.1f} bas:{bottom_brightness:.1f}")
    
    max_brightness = max(top_brightness, middle_brightness, bottom_brightness)
    if max_brightness < 60:  # Trop sombre - feu non allumé
        return "unknown"
    
    if top_brightness == max_brightness:
        return "red"
    elif bottom_brightness == max_brightness:
        return "green"
    else:
        return "yellow"


def analyze_advanced_alerts(frame, detections, prev_detections, frame_count):
    """
    Analyse avancée des détections pour alertes pertinentes
    
    Retourne:
        Dictionnaire d'alertes avancées
    """
    alerts = {
        "person_groups": [],
        "fast_movements": [],
        "traffic_lights": [],
        "obstacles_ahead": []
    }
    
    # 1. Détecter les groupes de personnes
    groups = detect_person_groups(detections)
    alerts["person_groups"] = groups
    
    # 2. Détecter les mouvements rapides
    if prev_detections:
        fast_movements = detect_fast_movement(detections, prev_detections)
        alerts["fast_movements"] = fast_movements
    
    # 3. Analyser les feux de circulation PIÉTONS uniquement
    # Heuristique : feux voiture = 3 disques empilés → très allongés (h/w > 2.2)
    #               feux piéton = 1-2 symboles   → plus carrés  (h/w <= 2.2)
    traffic_lights = [d for d in detections if d["class"] == "traffic light"]
    for tl in traffic_lights:
        box = tl["box"]
        h = box["y2"] - box["y1"]
        w = box["x2"] - box["x1"]
        ratio = h / w if w > 0 else 999
        print(f"🚦 Feu détecté - ratio h/w={ratio:.2f} {'→ voiture ignoré' if ratio > 2.2 else '→ piéton analysé'}")
        # Ignorer les feux voiture (très allongés verticalement)
        if ratio > 2.2:
            continue
        color = detect_traffic_light_color(frame, box)
        print(f"   Couleur: {color}")
        # N'annoncer QUE le rouge
        if color == "red":
            alerts["traffic_lights"].append({
                "color": "red",
                "position_x": box["center_x"],
                "side": "gauche" if box["center_x"] < FRAME_WIDTH / 2 else "droite"
            })
    
    # 4. Détecter obstacles proches (objets dans le tiers inférieur de l'image)
    obstacle_classes = ["person", "car", "truck", "bus", "bicycle", "motorcycle", "dog", "chair", "bench"]
    for det in detections:
        if det["class"] in obstacle_classes:
            # Si l'objet est dans le tiers inférieur de l'image (proche)
            if det["box"]["y2"] > FRAME_HEIGHT * 0.66:
                alerts["obstacles_ahead"].append({
                    "class": det["class"],
                    "position_x": det["box"]["center_x"],
                    "side": "gauche" if det["box"]["center_x"] < FRAME_WIDTH / 2 else "droite",
                    "size": "large" if det["box"]["width"] > FRAME_WIDTH * 0.3 else "normal"
                })
    
    return alerts


def analyze_weather_conditions(frame):
    """
    Analyse les conditions de luminosité et de contraste
    
    Paramètres:
        frame: Image BGR OpenCV
        
    Retourne:
        Dictionnaire avec alertes météo (luminosité, brouillard, etc.)
    """
    # Luminosité moyenne (canal V du HSV)
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    brightness = hsv[:,:,2].mean()
    
    # Contraste (écart-type des niveaux de gris)
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    contrast = gray.std()
    
    # Seuils ajustés pour détecter facilement lors des tests
    alerts = {
        "low_light": bool(brightness < 120),      # Déclenché quand on cache partiellement la caméra
        "fog_or_blur": bool(contrast < 20),       # Flou détecté plus facilement
        "very_dark": bool(brightness < 70),       # Très sombre (main devant caméra)
        "brightness": round(float(brightness), 1),
        "contrast": round(float(contrast), 1)
    }
    
    return alerts


# ============================================================================
# Boucle principale
# ============================================================================

async def stream_frames():
    """
    Capture, détecte et diffuse les frames en boucle
    
    Étapes:
        1. Capture frame OpenCV
        2. Inférence YOLOv8
        3. Annotation de l'image
        4. Encodage JPEG + Base64
        5. Analyse météo (luminosité/contraste)
        6. Analyse avancée (groupes, mouvement, feux)
        7. Diffusion JSON via WebSocket
    """
    global previous_detections
    frame_count = 0
    
    while True:
        try:
            ret, frame = cap.read()
            
            if not ret:
                print("⚠️ Erreur lecture caméra")
                await asyncio.sleep(0.5)
                continue
            
            # Redimensionner si nécessaire
            frame = cv2.resize(frame, (FRAME_WIDTH, FRAME_HEIGHT))
            
            # Inférence YOLO avec mesure du temps
            t0 = time.time()
            results = model(frame, verbose=False)
            inference_time_ms = (time.time() - t0) * 1000  # Convertir en millisecondes
            

            # Extraire les détections au format JSON
            detections = extract_detections(results)
            
            # Analyser les conditions météo
            weather_alerts = analyze_weather_conditions(frame)
            
            # Analyser les alertes avancées (groupes, mouvements, feux)
            advanced_alerts = analyze_advanced_alerts(frame, detections, previous_detections, frame_count)
            
            # Mettre à jour l'historique des détections pour le tracking
            previous_detections = {
                det["class"]: (det["box"]["center_x"], det["box"]["center_y"]) 
                for det in detections
            }
            
            # Encoder en JPEG puis Base64
            # Encoder la frame brute (Android dessine ses propres boîtes via AnnotatedImageView)
            _, buffer = cv2.imencode('.jpg', frame,
                                    [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
            b64_image = base64.b64encode(buffer).decode('utf-8')
            
            # Log taille périodique
            if frame_count % 50 == 0:
                msg_size_kb = len(b64_image) / 1024
                print(f"📊 Frame {frame_count} | Taille: {msg_size_kb:.1f} KB")
            
            # Créer le message JSON
            message = json.dumps({
                "type": "frame",
                "timestamp": datetime.now().isoformat(),
                "image": b64_image,
                "detections": detections,
                "weather_alerts": weather_alerts,
                "advanced_alerts": advanced_alerts,
                "frame_id": frame_count,
                "inference_time_ms": round(inference_time_ms, 1)
            })
            
            # Diffuser à tous les clients
            await broadcast(message)
            
            # Log périodique
            frame_count += 1
            if frame_count % 10 == 0:
                print(f"📡 Frame {frame_count} | {len(detections)} détection(s)")
                print(f"   💡 Luminosité: {weather_alerts['brightness']:.1f} | "
                      f"Contraste: {weather_alerts['contrast']:.1f}")
                
                if weather_alerts['very_dark']:
                    print("   ⚠️  TRÈS SOMBRE")
                elif weather_alerts['low_light']:
                    print("   ⚠️  Lumière faible")
                    
                if weather_alerts['fog_or_blur']:
                    print("   🌫️  Brouillard/flou détecté")
            
            # Respecter le FPS cible
            await asyncio.sleep(1.0 / FPS_TARGET)
            
        except Exception as e:
            print(f"❌ Erreur dans stream_frames: {e}")
            await asyncio.sleep(1)


async def main():
    """
    Lance le serveur WebSocket et démarre la boucle de capture
    
    Initialise le serveur WebSocket sur 0.0.0.0:8765 et démarre
    la boucle de capture/diffusion des frames. Affiche les informations
    de connexion (localhost et réseau local).
    
    Retourne:
        Coroutine qui s'exécute indéfiniment jusqu'à interruption
    """
    async with websockets.serve(handle_client, "0.0.0.0", 8765):
        print("\n" + "="*60)
        print("🌐 WebSocket serveur démarré")
        print("📍 Localhost: ws://localhost:8765")
        print("📍 Réseau local: ws://192.168.x.x:8765")
        print("="*60 + "\n")
        
        await stream_frames()


# ============================================================================
# Point d'entrée
# ============================================================================

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n\n🛑 Arrêt du serveur")
        cap.release()
        print("✅ Ressources libérées")

