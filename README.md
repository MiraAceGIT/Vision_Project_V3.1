# Blind Helmet — Vision System

Casque d'assistance visuelle pour malvoyants, développé en binôme dans le cadre du TIPE.

**Partie vision (ce repo)** — La caméra filme, YOLOv8 détecte les objets, l'application Android restitue les alertes en vocal (TTS français).

**Partie haptique (pas ce repo)** — 2 capteurs ultrasons HC-SR04 sur ESP32 mesurent la distance aux obstacles et déclenchent un moteur vibrant + buzzer selon 4 zones de proximité (120 cm / 70 cm / 35 cm). Les deux parties ne communiquent pas entre elles : elles fonctionnent en parallèle de manière autonome.

---

## Architecture

```
Webcam PC
    │
    ▼
vision_server.py      ← OpenCV + YOLOv8n + WebSocket (port 8765)
    │  JSON {type, detections, image_base64, inference_time_ms}
    ▼
App Android           ← AnnotatedImageView + AudioFeedback (TTS français)
```

**Mode actuel** — webcam PC + émulateur Android Studio (ou téléphone physique sur le même réseau)  
**Mode futur** — ESP32-CAM remplace la webcam (voir [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md))

---

## Connexion — version actuelle

### Étape 1 — Lancer le serveur Python

```bash
# Dans le dossier racine du projet
pip install -r requirements.txt   # une seule fois
python vision_server.py
```

Le terminal affiche :
```
📍 Localhost: ws://localhost:8765
📍 Réseau local: ws://192.168.x.x:8765
```
Laisser ce terminal ouvert.

### Étape 2a — Émulateur Android Studio (par défaut)

`MainActivity.java` ligne 50 est déjà configurée :
```java
private static final String ESP32_URL = "ws://10.0.2.2:8765";
```
`10.0.2.2` est l'adresse spéciale que l'émulateur Android utilise pour joindre `localhost` du PC.

- Ouvrir `app-android/` dans Android Studio
- Build & Run sur l'émulateur

### Étape 2b — Téléphone physique (même réseau WiFi)

1. Trouver l'IP du PC :
   ```powershell
   ipconfig   # chercher l'adresse IPv4 de l'interface WiFi, ex: 192.168.1.42
   ```
2. Dans `app-android/app/src/main/java/com/blind_helmet/app/MainActivity.java`, ligne 50 :
   ```java
   private static final String ESP32_URL = "ws://192.168.1.42:8765";  // ← mettre l'IP du PC
   ```
3. Autoriser le port dans le pare-feu Windows :
   ```powershell
   New-NetFirewallRule -DisplayName "Vision Server" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow
   ```
4. Build & Run sur le téléphone physique (USB ou WiFi ADB)

### Vérification

L'app doit afficher :
- L'image caméra en temps réel avec les boîtes de détection
- Le badge de connexion vert en haut
- Les alertes vocales en français quand un objet est détecté

---

## Performances

| Métrique | Valeur |
|---|---|
| Latence totale | ~120 ms |
| FPS moyen | ~8 fps |
| Classes détectées | 15 (COCO subset) |
| Qualité JPEG | 75 |
| Résolution | 640×480 |

---

## Structure du projet

```
├── vision_server.py             # Serveur principal (capture + YOLOv8 + WebSocket)
├── requirements.txt             # Dépendances Python
├── yolov8n.pt                   # Poids du modèle YOLOv8n (nano)
│
├── app-android/                 # Application Android Studio
│   └── app/src/main/
│       ├── java/com/blind_helmet/app/
│       │   ├── MainActivity.java        # Orchestrateur principal
│       │   ├── WebSocketManager.java    # Connexion réseau OkHttp
│       │   ├── AudioFeedback.java       # TTS + vibrations
│       │   ├── DetectionData.java       # Structures de données JSON
│       │   ├── AnnotatedImageView.java  # Vue avec boîtes de détection
│       │   ├── YOLOHelper.java          # Inférence TFLite (désactivée)
│       │   └── SpeechController.java   # Reconnaissance vocale (désactivée)
│       └── assets/
│           ├── class_names.txt          # Noms des 80 classes COCO
│           ├── model_info.txt           # Métadonnées modèle TFLite
│           └── models/                  # Modèle TFLite (mode autonome futur)
│
├── firmware/esp32_cam/          # Firmware C++ pour ESP32-CAM (futur)
│   ├── main.cpp
│   ├── camera.h
│   └── websocket.h
│
├── scripts/                     # Utilitaires export modèle
│   └── colab_export_yolov8_tflite.py
│
├── tests/                       # Tests
│   └── test_vision_server.py
│
└── docs/                        # Documentation
    ├── DEPLOYMENT.md            # Guide connexion + déploiement ESP32-CAM
    ├── ARCHITECTURE.md          # Schéma d'architecture et flux de données
    ├── RAPPORT_PARTIE_LOGICIELLE.md  # Rapport technique
    ├── DOCUMENT_ANNEXE.md       # Document annexe
    └── SOUTENANCE.md            # Notes présentation TIPE
```

---

## Dépendances clés

**Python**
- `ultralytics` — YOLOv8
- `opencv-python` — Capture caméra
- `websockets` — Transport temps réel
- `torch` — Inférence modèle

**Android**
- TensorFlow Lite 2.13
- OkHttp 4.10 (WebSocket)
- Gson 2.10 (JSON)

---

## Documentation

- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — Guide connexion détaillé + déploiement ESP32-CAM
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — Schéma d'architecture et flux de données
- [`docs/RAPPORT_PARTIE_LOGICIELLE.md`](docs/RAPPORT_PARTIE_LOGICIELLE.md) — Rapport technique complet
- [`docs/DOCUMENT_ANNEXE.md`](docs/DOCUMENT_ANNEXE.md) — Document annexe
- [`docs/SOUTENANCE.md`](docs/SOUTENANCE.md) — Notes présentation TIPE
