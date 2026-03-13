# Guide de connexion et déploiement

---

## Mode actuel — Webcam PC + Android

### Prérequis

- Python 3.8+ avec les dépendances : `pip install -r requirements.txt`
- Android Studio avec un émulateur configuré **ou** un téléphone Android sur le même réseau WiFi
- Webcam branchée sur le PC

### Lancer le serveur

```bash
python vision_server.py
```

Sortie attendue :
```
📷 Caméra initialisée (640x480 @ 8 FPS)
🤖 Modèle YOLOv8n chargé
📡 Serveur WebSocket démarré
📍 Localhost: ws://localhost:8765
📍 Réseau local: ws://192.168.x.x:8765
```

### Connexion depuis l'émulateur Android Studio

Configuration par défaut dans `MainActivity.java` ligne 50 :

```java
private static final String ESP32_URL = "ws://10.0.2.2:8765";
```

`10.0.2.2` est l'alias interne que l'émulateur Android utilise pour accéder à `localhost` du PC hôte. **Rien à modifier.**

- Ouvrir `app-android/` dans Android Studio → Build & Run sur l'émulateur.

### Connexion depuis un téléphone physique

Le téléphone et le PC doivent être sur le **même réseau WiFi**.

**1. Trouver l'IP du PC :**
```powershell
ipconfig
# Chercher : "Adresse IPv4" sous l'interface WiFi → ex: 192.168.1.42
```

**2. Modifier la constante dans `MainActivity.java` ligne 50 :**
```java
private static final String ESP32_URL = "ws://192.168.1.42:8765";  // IP du PC
```

**3. Autoriser le port dans le pare-feu Windows (une seule fois) :**
```powershell
New-NetFirewallRule -DisplayName "Vision Server 8765" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow
```

**4. Build & Run** sur le téléphone physique (USB ou WiFi ADB).

### Checklist de validation

- [ ] Terminal Python affiche les connexions entrantes
- [ ] Badge de connexion vert dans l'app
- [ ] Flux vidéo temps réel visible
- [ ] Boîtes de détection dessinées sur l'image
- [ ] Alertes vocales françaises au-delà d'un seuil de confiance

---

## Troubleshooting

**App affiche "Connexion..." en boucle**
- Vérifier que `vision_server.py` est bien lancé
- Sur téléphone physique : vérifier que l'IP dans `MainActivity.java` est correcte
- Vérifier le pare-feu Windows (port 8765 en entrée TCP)
- Vérifier que PC et téléphone sont sur le même réseau WiFi (pas 5 GHz vs 2.4 GHz)

**Flux vidéo saccadé / latence élevée**
- Réduire `JPEG_QUALITY` dans `vision_server.py` (actuellement 75)
- Réduire `TARGET_FPS` (actuellement 8)
- Vérifier la force du signal WiFi

**Aucune détection vocale**
- Vérifier que le volume du téléphone est activé
- Le cooldown entre annonces est de 30 secondes par classe — attendre ou réduire `ANNOUNCE_COOLDOWN_MS` dans `MainActivity.java`

**Webcam non détectée**
- `cv2.VideoCapture(0)` → essayer `cv2.VideoCapture(1)` si plusieurs webcams

---

## Évolution future — Mode ESP32-CAM

Ce mode remplace la webcam PC par un module ESP32-CAM embarqué dans le casque.

### Étape 1 — Flasher le firmware

```
# Ouvrir firmware/esp32_cam/main.cpp dans Arduino IDE
# Board : AI Thinker ESP32-CAM
# Modifier les credentials WiFi :
const char* ssid = "VOTRE_SSID";
const char* password = "VOTRE_PASSWORD";
# Tools → Port → COM3 → Upload
```

**Vérification** : Serial Monitor affiche `Camera init OK` et l'IP assignée.

### Étape 2 — Connecter le serveur Python à l'ESP32

Dans `vision_server.py`, remplacer la source caméra :

```python
# Remplacer :
cap = cv2.VideoCapture(0)
# Par :
cap = cv2.VideoCapture("http://192.168.X.X/stream")  # IP de l'ESP32
```

### Étape 3 — Configurer l'app Android

Même procédure que le téléphone physique ci-dessus : mettre l'IP du PC (qui fait tourner le serveur Python) dans `MainActivity.java` ligne 50.

### Troubleshooting ESP32

- **Ne se connecte pas au WiFi** : réseau 2.4 GHz obligatoire (pas 5 GHz)
- **Flux coupé** : réduire la qualité JPEG dans `camera.h` (`FRAMESIZE_VGA` ou `FRAMESIZE_QVGA`)

---

## Optionnel — Inférence locale Android (sans serveur Python)

Make YOLOv8 tourner directement sur le téléphone via TFLite — `YOLOHelper.java` est déjà présent mais désactivé.

1. Exporter le modèle via `scripts/colab_export_yolov8_tflite.py` sur Google Colab
2. Placer `yolov8n.tflite` dans `app-android/app/src/main/assets/models/`
3. Dans `MainActivity.java`, remplacer `yoloHelper = null` par :
   ```java
   yoloHelper = new YOLOHelper(this);
   ```
4. Adapter le pipeline pour utiliser `yoloHelper.runInference()` au lieu du callback WebSocket
