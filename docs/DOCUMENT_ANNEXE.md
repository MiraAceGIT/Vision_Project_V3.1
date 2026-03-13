# Document Annexe — Extraits de code  
### Projet TIPE — Partie logicielle & IA

---

## cf. 1a — Groupes de personnes

**Fichier source :** [vision_server.py](vision_server.py)

```python
def detect_person_groups(detections):
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

            # Distance euclidienne entre les centres des deux personnes
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
```

---

## cf. 1b — Mouvement rapide

> Vitesse = distance / temps (0.125s = 1/8 FPS). Si > 200px/s → alerte.

**Fichier source :** [vision_server.py](vision_server.py)

```python
def detect_fast_movement(current_detections, prev_detections, frame_time_delta=0.125):
    alerts = []
    SPEED_THRESHOLD = 200  # pixels par seconde

    animals  = ["dog", "cat", "horse", "cow", "sheep"]
    vehicles = ["car", "truck", "bus", "motorcycle", "bicycle"]

    for curr in current_detections:
        if curr["class"] not in animals + vehicles:
            continue

        curr_x = curr["box"]["center_x"]
        curr_y = curr["box"]["center_y"]

        for prev_class, prev_pos in prev_detections.items():
            if prev_class != curr["class"]:
                continue

            dx = curr_x - prev_pos[0]
            dy = curr_y - prev_pos[1]
            distance = (dx**2 + dy**2) ** 0.5
            speed = distance / frame_time_delta   # px/s

            if speed > SPEED_THRESHOLD:
                direction = "approche" if dx > 0 else "s'éloigne"
                alerts.append({
                    "type": "vehicle_moving",
                    "vehicle": curr["class"],
                    "direction": direction
                })

    return alerts
```

---

## cf. 1c — Couleur feu piéton (HSV)

> Masques HSV : rouge (0-15° + 160-180°), vert (35-90°), jaune (15-35°). Méthode secours : zone la plus lumineuse (haut=rouge, bas=vert).

**Fichier source :** [vision_server.py](vision_server.py)

```python
def detect_traffic_light_color(frame, box):
    x1, y1, x2, y2 = box["x1"], box["y1"], box["x2"], box["y2"]
    roi = frame[y1:y2, x1:x2]   # Extraire la région du feu dans l'image
    
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)

    # Rouge : Hue 0-15° et 160-180° (rouge est aux deux extrémités du cercle HSV)
    red_mask1 = cv2.inRange(hsv, np.array([0, 50, 80]), np.array([15, 255, 255]))
    red_mask2 = cv2.inRange(hsv, np.array([160, 50, 80]), np.array([180, 255, 255]))
    red_pixels = cv2.countNonZero(red_mask1) + cv2.countNonZero(red_mask2)

    green_mask = cv2.inRange(hsv, np.array([35, 50, 80]), np.array([90, 255, 255]))
    green_pixels = cv2.countNonZero(green_mask)

    total_pixels = roi.shape[0] * roi.shape[1]
    threshold = total_pixels * 0.03  # 3% des pixels minimum

    if red_pixels > threshold and red_pixels >= green_pixels:
        return "red"
    elif green_pixels > threshold:
        return "green"

    # --- Méthode 2 (secours) : zone la plus lumineuse ---
    h = roi.shape[0]
    top_brightness = roi[:h//3, :].mean()       # Zone haute  → rouge
    bottom_brightness = roi[2*h//3:, :].mean()  # Zone basse  → vert
    
    return "red" if top_brightness > bottom_brightness else "green"
```

---

## cf. 1d — Conditions météo

> Canal V du HSV = luminosité. Écart-type niveaux de gris = contraste.

**Fichier source :** [vision_server.py](vision_server.py)

```python
def analyze_weather_conditions(frame):
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    brightness = hsv[:,:,2].mean()          # Moyenne du canal V (0-255)

    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    contrast = gray.std()                   # Écart-type (faible = image uniforme = brouillard)

    return {
        "very_dark": bool(brightness < 70),
        "low_light": bool(brightness < 120),
        "fog_or_blur": bool(contrast < 20),
        "brightness": round(float(brightness), 1),
        "contrast": round(float(contrast), 1)
    }
```

---

## cf. 2 — Encodage JSON

**Fichier source :** [vision_server.py](vision_server.py)

```python
message = json.dumps({
    "type":             "frame",
    "timestamp":        datetime.now().isoformat(),
    "image":            b64_image,          # Image encodée en Base64 (texte)
    "detections":       detections,         # Liste des objets détectés par YOLOv8
    "weather_alerts":   weather_alerts,     # Luminosité, contraste, brouillard
    "advanced_alerts":  advanced_alerts,    # Groupes, mouvements, feux
    "frame_id":         frame_count,
    "inference_time_ms": round(inference_time_ms, 1)
})
```

---

## cf. 3 — Envoi via WebSocket

**Fichier source :** [vision_server.py](vision_server.py)

```python
# Liste globale des clients connectés
clients = set()

async def handle_client(websocket):
    """Appelé à chaque nouvelle connexion Android"""
    clients.add(websocket)       # Ajouter à la liste
    print(f"✅ Client connecté: {websocket.remote_address}")
    try:
        await websocket.wait_closed()   # Attendre déconnexion
    finally:
        clients.remove(websocket)       # Retirer de la liste

async def broadcast(message):
    """Envoie le message JSON à TOUS les clients en parallèle"""
    if clients:
        await asyncio.gather(
            *[client.send(message) for client in clients],
            return_exceptions=True   # Un client qui plante ne bloque pas les autres
        )

await broadcast(message)
```

---

## cf. 4 — Parsing JSON côté Android

**Fichier source :** [WebSocketManager.java](app-android/app/src/main/java/com/blind_helmet/app/WebSocketManager.java)

```java
JSONObject json = new JSONObject(message);

JSONArray detectionsArray = json.getJSONArray("detections");
for (int i = 0; i < detectionsArray.length(); i++) {
    JSONObject d = detectionsArray.getJSONObject(i);
    Detection det = new Detection();
    det.className = d.getString("class");           // "person"
    det.confidence = (float) d.getDouble("confidence"); // 0.87
    det.position = d.getString("position");         // "gauche"
    
    JSONArray box = d.getJSONArray("box");  // [120, 85, 245, 380]
    det.x1 = box.getInt(0);  // 120
    det.y1 = box.getInt(1);  // 85
    det.x2 = box.getInt(2);  // 245
    det.y2 = box.getInt(3);  // 380
    
    frame.detections.add(det);
}
```

---

## cf. 5 — Affichage confiance (boîtes colorées)

**Fichier source :** [AnnotatedImageView.java](app-android/app/src/main/java/com/blind_helmet/app/AnnotatedImageView.java)

```java
@Override
protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    for (Detection det : detections) {
        float x1 = det.x1 * scaleX;
        float y1 = det.y1 * scaleY;
        float x2 = det.x2 * scaleX;
        float y2 = det.y2 * scaleY;

        // Couleur selon confiance
        if (det.confidence > 0.8f) {
            boxPaint.setColor(Color.GREEN);    // Vert = confiant
        } else if (det.confidence > 0.6f) {
            boxPaint.setColor(Color.YELLOW);   // Jaune = moyen
        } else {
            boxPaint.setColor(Color.RED);      // Rouge = incertain
        }

        canvas.drawRect(x1, y1, x2, y2, boxPaint);
        canvas.drawText(det.className + " " + (int)(det.confidence * 100) + "%", x1, y1 - 10, textPaint);
    }
}
```

---

## cf. 6 — Synthèse vocale TTS

**Fichier source :** [AudioFeedback.java](app-android/app/src/main/java/com/blind_helmet/app/AudioFeedback.java)

```java
tts = new TextToSpeech(context, status -> {
    if (status == TextToSpeech.SUCCESS) {
        tts.setLanguage(Locale.FRENCH);  // Voix française
        isReady = true;
    }
});

public void speakAlert(String text) {
    if (isReady && tts != null) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);  // Prononce immédiatement
    }
}
```

---

## cf. 7 — Conversion yolov8n.pt → yolov8n_int8.tflite

> **int8=True** = quantization : 32 bits → 8 bits. Modèle 4× plus petit, 2-3× plus rapide, ~2% moins précis.

**Fichier source :** [export_tflite.py](export_tflite.py)

```python
from ultralytics import YOLO

model = YOLO("yolov8n.pt")           # Modèle actuel (PyTorch, tourne sur PC)
model.export(format="tflite", int8=True)
# → génère yolov8n_int8.tflite
# int8=True = quantization : réduit la précision 32bits → 8bits
#  résultat : modèle 4x plus petit, 2-3x plus rapide, ~2% moins précis
```

---

## cf. 8 — Anti-spam temporel

> Chaque classe d'objet n'est annoncée qu'une fois toutes les 3 secondes.

**Fichier source :** [MainActivity.java](app-android/app/src/main/java/com/blind_helmet/app/MainActivity.java)

```java
// Map qui stocke le dernier temps d'annonce pour chaque classe
private static final Map<String, Long> lastAnnouncedTime = new ConcurrentHashMap<>();
private static final long ANNOUNCEMENT_COOLDOWN_MS = 3000;  // 3 secondes

// Vérifie si on peut annoncer cette classe maintenant
private boolean canAnnounce(String className) {
    long currentTime = System.currentTimeMillis();
    Long lastTime = lastAnnouncedTime.get(className);
    
    if (lastTime == null || (currentTime - lastTime) >= ANNOUNCEMENT_COOLDOWN_MS) {
        lastAnnouncedTime.put(className, currentTime);
        return true;   // Suffisamment de temps écoulé
    }
    return false;      // Trop récent, on skip
}

if (canAnnounce(det.className)) {
    audioFeedback.speakAlert(det.className + " " + det.position);
}
```

---

## cf. 9 — Détection de mouvement

> Si un objet se déplace de ≥50 pixels, on le réannonce immédiatement (même si cooldown pas écoulé).

**Fichier source :** [MainActivity.java](app-android/app/src/main/java/com/blind_helmet/app/MainActivity.java)

```java
// Map qui stocke la dernière position connue de chaque objet (par classe)
private static final Map<String, Integer> lastPosition = new ConcurrentHashMap<>();
private static final int MOVEMENT_THRESHOLD_PX = 50;

// Vérifie si l'objet a bougé significativement depuis la dernière annonce
private boolean hasMovedSignificantly(String className, int currentCenterX) {
    Integer lastX = lastPosition.get(className);
    
    if (lastX == null) {
        lastPosition.put(className, currentCenterX);
        return false;  // Première détection, pas de mouvement connu
    }
    
    int deltaX = Math.abs(currentCenterX - lastX);
    if (deltaX >= MOVEMENT_THRESHOLD_PX) {
        lastPosition.put(className, currentCenterX);  // Mise à jour position
        return true;   // L'objet a bougé
    }
    return false;      // Toujours au même endroit
}

int centerX = (det.x1 + det.x2) / 2;
if (canAnnounce(det.className) || hasMovedSignificantly(det.className, centerX)) {
    audioFeedback.speakAlert(det.className + " " + det.position);
}
```

---

## cf. 10 — Table de traduction COCO

> 80 classes COCO traduites en français pour le TTS.

**Fichier source :** [AudioFeedback.java](app-android/app/src/main/java/com/blind_helmet/app/AudioFeedback.java)

```java
// Map statique des 80 classes COCO
private static final Map<String, String> FRENCH_LABELS = new HashMap<String, String>() {{
    // Personnes & animaux
    put("person", "Personne");
    put("cat", "Chat");
    put("dog", "Chien");
    // Véhicules
    put("car", "Voiture");
    put("truck", "Camion");
    put("bus", "Bus");
    put("motorcycle", "Moto");
    put("bicycle", "Vélo");
    // Signalisation
    put("traffic light", "Feu");
    put("stop sign", "Stop");
    // ... 70 autres classes
}};

private String translateToFrench(String englishClass) {
    return FRENCH_LABELS.getOrDefault(englishClass, englishClass);
}

public void speakAlert(String className, String position) {
    String frenchName = translateToFrench(className);
    speak(frenchName + " " + position);
}
```

---

## cf. 11 — Clustering personnes

> Algorithme de proximité : si ≥ 3 personnes à moins de 150px → groupe.

**Fichier source :** [vision_server.py](vision_server.py)

```python
def detect_person_groups(detections):
    persons = [d for d in detections if d["class"] == "person"]
    if len(persons) < 3:
        return []  # Pas assez de personnes pour former un groupe

    groups = []
    visited = set()  # Ensemble des indices déjà inclus dans un groupe
    PROXIMITY_THRESHOLD = 150  # pixels

    for i, p1 in enumerate(persons):
        if i in visited:
            continue  # Déjà dans un groupe, on passe

        group = [p1]
        visited.add(i)

        # Chercher toutes les autres personnes proches de p1
        for j, p2 in enumerate(persons):
            if j <= i or j in visited:
                continue

            # Distance euclidienne entre centres des boîtes
            dx = p1["box"]["center_x"] - p2["box"]["center_x"]
            dy = p1["box"]["center_y"] - p2["box"]["center_y"]
            dist = (dx**2 + dy**2) ** 0.5

            if dist < PROXIMITY_THRESHOLD:
                group.append(p2)
                visited.add(j)

        # Si ≥3 personnes dans ce groupe → alerte
        if len(group) >= 3:
            avg_x = sum(p["box"]["center_x"] for p in group) / len(group)
            groups.append({"count": len(group), "side": "gauche" if avg_x < 320 else "droite"})

    return groups
```

---

## cf. 12 — Calcul vitesse

> Vitesse (px/s) = distance euclidienne / temps entre 2 frames.

**Fichier source :** [vision_server.py](vision_server.py)

```python
# Dictionnaire global pour stocker les positions précédentes
previous_positions = {}  # { "car": (x, y), "dog": (x, y), ... }

def detect_fast_movement(current_detections, frame_time_delta=0.125):
    alerts = []
    SPEED_THRESHOLD = 200  # pixels par seconde

    animals  = ["dog", "cat", "horse", "cow", "sheep"]
    vehicles = ["car", "truck", "bus", "motorcycle", "bicycle"]

    for det in current_detections:
        class_name = det["class"]
        if class_name not in animals + vehicles:
            continue  # On ne surveille que véhicules et animaux

        curr_x, curr_y = det["box"]["center_x"], det["box"]["center_y"]

        # Vérifier si on a une position précédente pour cette classe
        if class_name in previous_positions:
            prev_x, prev_y = previous_positions[class_name]

            # Calcul de distance (théorème de Pythagore)
            dx = curr_x - prev_x
            dy = curr_y - prev_y
            distance = (dx**2 + dy**2) ** 0.5

            # Calcul de vitesse
            speed = distance / frame_time_delta  # px/s

            if speed > SPEED_THRESHOLD:
                alerts.append({"object": class_name, "speed_px_s": round(speed, 1)})

        # Mise à jour position pour le prochain frame
        previous_positions[class_name] = (curr_x, curr_y)

    return alerts
```

---

## cf. 13 — Analyse HSV

> Espaceouleur HSV : Hue (teinte), Saturation, Value (luminosité). Permet de détecter les couleurs indépendamment de la luminosité.

**Fichier source :** [vision_server.py](vision_server.py)

```python
def detect_traffic_light_color(frame, box):
    """Analyse la couleur d'un feu (rouge/vert) en HSV."""
    x1, y1, x2, y2 = box["x1"], box["y1"], box["x2"], box["y2"]
    roi = frame[y1:y2, x1:x2]  # Extraire la région d'intérêt
    
    if roi.size == 0 or roi.shape[0] < 5 or roi.shape[1] < 5:
        return "unknown"

    # Conversion BGR → HSV
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)

    # Masques de couleur (HSV = Teinte, Saturation, Valeur)
    # Rouge : 0-15° et 160-180° (rouge est aux deux bouts du cercle HSV)
    red_mask1   = cv2.inRange(hsv, np.array([0,   50, 80]), np.array([15,  255, 255]))
    red_mask2   = cv2.inRange(hsv, np.array([160, 50, 80]), np.array([180, 255, 255]))
    red_pixels  = cv2.countNonZero(red_mask1) + cv2.countNonZero(red_mask2)

    # Vert : 35-90°
    green_mask  = cv2.inRange(hsv, np.array([35, 50, 80]), np.array([90,  255, 255]))
    green_pixels = cv2.countNonZero(green_mask)

    # Jaune : 15-35°
    yellow_mask  = cv2.inRange(hsv, np.array([15, 50, 80]), np.array([35,  255, 255]))
    yellow_pixels = cv2.countNonZero(yellow_mask)

    # Seuil : au moins 3% des pixels de la couleur
    total_pixels = roi.shape[0] * roi.shape[1]
    threshold = total_pixels * 0.03

    if red_pixels > threshold and red_pixels >= green_pixels:
        return "red"       # Feu rouge détecté
    elif green_pixels > threshold and green_pixels > red_pixels:
        return "green"     # Feu vert détecté
    elif yellow_pixels > threshold:
        return "yellow"    # Feu orange détecté

    # Méthode de secours : analyse de luminosité par zone
    h = roi.shape[0]
    top_brightness    = roi[:h//3, :].mean()       # Zone haute
    bottom_brightness = roi[2*h//3:, :].mean()     # Zone basse
    
    max_b = max(top_brightness, bottom_brightness)
    if max_b < 60:
        return "unknown"  # Trop sombre
    
    if top_brightness == max_b:
        return "red"      # Zone haute allumée = rouge
    else:
        return "green"    # Zone basse allumée = vert
```

---

## cf. 14 — Obstacles proches

> Objet dans le tiers bas de l'image (y2 > 66% de hauteur) = très proche.

**Fichier source (Python) :** [vision_server.py](vision_server.py)

```python
def is_obstacle_close(detection, frame_height):
    y2 = detection["box"]["y2"]       # Bord bas de la boîte
    threshold = frame_height * 0.66   # 66% de la hauteur de l'image
    return y2 > threshold             # Si y2 est dans le tiers bas = proche

for det in detections:
    det["proximity"] = "proche" if is_obstacle_close(det, 480) else "loin"
```

**Fichier source (Android) :** [MainActivity.java](app-android/app/src/main/java/com/blind_helmet/app/MainActivity.java)

```java
if ("proche".equals(det.proximity)) {
    audioFeedback.speakAlert(det.className + " très proche !");
}
```

---

## cf. 15 — WebSocket : connexion persistante

> **WebSocket** : 1 handshake initial, canal reste ouvert. **HTTP** : 8 handshakes/sec (8 FPS impossible).

**Code Python (serveur) :**

**Fichier source :** [vision_server.py](vision_server.py)

```python
import websockets
import asyncio

clients = set()

async def handle_client(websocket):
    clients.add(websocket)  # Connexion ouverte
    try:
        await websocket.wait_closed()
    finally:
        clients.remove(websocket)

async def broadcast(message):
    if clients:
        await asyncio.gather(*[client.send(message) for client in clients])

while True:
    await broadcast(json_message)  # Envoi direct, pas de reconnexion
    await asyncio.sleep(0.125)     # 8 FPS
```

**Code Android (client) :**

**Fichier source :** [WebSocketManager.java](app-android/app/src/main/java/com/blind_helmet/app/WebSocketManager.java)

```java
webSocket = client.newWebSocket(request, new WebSocketListener() {
    @Override
    public void onMessage(WebSocket ws, String text) {
        // Appelé 8×/sec, canal toujours ouvert
        parseJSON(text);
    }
});
```

---

## cf. 16 — NumPy : tableaux d'images

> Image = tableau 3D (H×L×3). NumPy = opérations vectorisées en C, 100× plus rapide que Python pur.

**Fichier source :** [vision_server.py](vision_server.py)

```python
import cv2
import numpy as np

# Capture webcam → tableau NumPy automatique
ret, frame = cap.read()
print(frame.shape)  # (480, 640, 3) = hauteur × largeur × BGR
print(frame.dtype)  # uint8 (0-255)

# Accès pixel
frame[100, 200, 2]  # Canal rouge à position (100, 200)

# Modifier tous les pixels rouges d'un coup (vectorisé)
frame[:, :, 2] = frame[:, :, 2] * 0.8  # Réduire rouge de 20%

# YOLOv8 accepte directement le tableau NumPy
results = model(frame)

# Calcul luminosité (pour météo)
hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
avg_brightness = hsv[:, :, 2].mean()  # Moyenne de 307 200 pixels en ~0.5ms
```

---

## cf. 17 — Boucle principale stream_frames()

> Flux principal : webcam → YOLOv8 → alertes → JSON → WebSocket (×8/sec).

**Fichier source :** [vision_server.py](vision_server.py)

```python
async def stream_frames():
    frame_count = 0
    
    while True:
        # 1. Capture webcam
        ret, frame = cap.read()
        frame = cv2.resize(frame, (640, 480))
        
        # 2. Inférence YOLOv8
        results = model(frame, verbose=False)
        detections = extract_detections(results)
        
        # 3. Alertes météo + avancées
        weather_alerts = analyze_weather_conditions(frame)
        advanced_alerts = analyze_advanced_alerts(frame, detections, previous_detections)
        
        # 4. Encodage image JPEG → Base64
        _, buffer = cv2.imencode('.jpg', results[0].plot())
        b64_image = base64.b64encode(buffer).decode('utf-8')
        
        # 5. Création du message JSON
        message = json.dumps({
            "image": b64_image,
            "detections": detections,
            "weather_alerts": weather_alerts,
            "advanced_alerts": advanced_alerts
        })
        
        # 6. Diffusion WebSocket à tous les clients
        await broadcast(message)
        await asyncio.sleep(0.125)  # 8 FPS
```

---

## cf. 18 — MainActivity : orchestration

> Chef d'orchestre : WebSocket → parsing → anti-spam → affichage → TTS.

**Fichier source :** [MainActivity.java](app-android/app/src/main/java/com/blind_helmet/app/MainActivity.java)

```java
public class MainActivity extends AppCompatActivity {
    
    private static final Map<String, Long> lastAnnouncedTime = new ConcurrentHashMap<>();
    private static final Map<String, Integer> lastPosition = new ConcurrentHashMap<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        audioFeedback = new AudioFeedback(this);
        imageView = findViewById(R.id.annotatedImageView);
        
        wsManager = new WebSocketManager("ws://10.0.2.2:8765", frame -> handleFrame(frame));
        wsManager.connect();
    }
    
    private void handleFrame(FrameData frame) {
        runOnUiThread(() -> imageView.setFrameData(frame));
        handleDetections(frame.detectionData);
    }
    
    private void handleDetections(DetectionData data) {
        for (Detection det : data.detections) {
            int centerX = (det.x1 + det.x2) / 2;  // Calculer centre de la boîte
            
            // cf. 8 (3s cooldown) + cf. 9 (50px mouvement)
            if (canAnnounce(det.className) || hasMovedSignificantly(det.className, centerX)) {
                audioFeedback.speakAlert(det.className + " " + det.position);
                lastAnnouncedTime.put(det.className, System.currentTimeMillis());
                lastPosition.put(det.className, centerX);
            }
        }
    }
}
```

---

## cf. 19 — Handler / Looper

> Permet de passer du thread WebSocket au thread UI. Obligatoire pour modifier `ImageView`, `TextView`.

**Fichier source :** [MainActivity.java](app-android/app/src/main/java/com/blind_helmet/app/MainActivity.java)

```java
// WebSocketManager appelle ce callback (thread WebSocket)
public void onFrameReceived(FrameData data) {
    // ❌ INTERDIT : imageView.setImageBitmap(data.image) → CRASH
    // "CalledFromWrongThreadException: Only the original thread that created
    //  a view hierarchy can touch its views."
    
    // ✅ Solution : basculer vers le thread UI
    runOnUiThread(() -> {
        imageView.setImageBitmap(data.image);  // OK maintenant
        imageView.invalidate();                // Force redessin
    });
    
    // Traitement non-UI (pas besoin de runOnUiThread)
    handleDetections(data.detections);
    audioFeedback.speakAlert("Personne");  // TTS = pas de contrainte UI
}
```

---

## cf. 20 — OkHttp : WebSocket Android

> Bibliothèque réseau Java/Android. Gère connexions WebSocket avec reconnexion automatique.

**Fichier source :** [WebSocketManager.java](app-android/app/src/main/java/com/blind_helmet/app/WebSocketManager.java)

```java
OkHttpClient client = new OkHttpClient();
Request request = new Request.Builder()
    .url("ws://10.0.2.2:8765")  // 10.0.2.2 = PC hôte depuis émulateur Android
    .build();

webSocket = client.newWebSocket(request, new WebSocketListener() {
    
    @Override
    public void onOpen(WebSocket ws, Response response) {
        Log.d(TAG, "✅ WebSocket connecté");
    }
    
    @Override
    public void onMessage(WebSocket ws, String text) {
        // ← Appelé automatiquement à chaque message (8×/sec)
        // Pas besoin de demander, le serveur pousse les données
        
        JSONObject json = new JSONObject(text);
        FrameData frame = parseFrame(json);
        callback.onFrameReceived(frame);
    }
    
    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        Log.e(TAG, "❌ Déconnecté: " + t.getMessage());
        scheduleReconnect();  // Reconnexion auto après 3s
    }
});
```

---

## cf. 21 — Parsing JSON Android

> Conversion texte JSON → objets Java manipulables.

**Fichier source :** [WebSocketManager.java](app-android/app/src/main/java/com/blind_helmet/app/WebSocketManager.java)

```java
private FrameData parseJSON(String jsonString) {
    JSONObject json = new JSONObject(jsonString);
    
    // 1. Champs simples (nombres, chaînes)
    int frameId = json.getInt("frame_id");
    double inferenceTime = json.getDouble("inference_time_ms");
    
    // 2. Tableau de détections
    JSONArray detectionsArray = json.getJSONArray("detections");
    List<Detection> detections = new ArrayList<>();
    
    for (int i = 0; i < detectionsArray.length(); i++) {
        JSONObject det = detectionsArray.getJSONObject(i);  // Un objet du tableau
        
        String className = det.getString("class");       // "person"
        float confidence = (float) det.getDouble("confidence"); // 0.87
        
        // Extraire le sous-tableau "box"
        JSONArray box = det.getJSONArray("box");  // [120, 85, 245, 380]
        int x1 = box.getInt(0);  // 120
        int y1 = box.getInt(1);  // 85
        int x2 = box.getInt(2);  // 245
        int y2 = box.getInt(3);  // 380
        
        detections.add(new Detection(className, confidence, x1, y1, x2, y2));
    }
    
    // 3. Image Base64 → décodage en Bitmap
    String imageBase64 = json.getString("image");
    byte[] imageBytes = Base64.decode(imageBase64, Base64.DEFAULT);
    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    
    // 4. Alertes avancées (groupes, feux, mouvements)
    JSONObject advancedAlerts = json.getJSONObject("advanced_alerts");
    // ... parsing groupes, feux, mouvements
    
    // 5. Assembler l'objet final
    FrameData frame = new FrameData();
    frame.frameId = frameId;
    frame.image = bitmap;
    frame.detections = detections;
    
    return frame;
}
```

---

*Document Annexe — Projet TIPE 2026*
