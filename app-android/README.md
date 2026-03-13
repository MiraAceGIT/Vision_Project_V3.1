# Application Android — Blind Helmet

Code source de l'application Android.

## Fichiers principaux

### Code Java (`app/src/main/java/com/blind_helmet/app/`)

| Fichier | Rôle |
|---|---|
| **MainActivity.java** | Chef d'orchestre. Gère la connexion WebSocket, affiche les frames, lance les alertes vocales. |
| **YOLOHelper.java** | Moteur d'inférence TFLite (désactivé en démo, le serveur fait l'inférence). |
| **WebSocketManager.java** | Client WebSocket. Reçoit les frames JPEG + détections JSON du serveur. |
| **AudioFeedback.java** | Synthèse vocale française + vibrations. Annonce les détections ("Voiture à droite"). |
| **SpeechController.java** | Reconnaissance vocale pour commandes ("OK", "répète"). |
| **DetectionData.java** | Container de données pour les détections (classe, confiance, boîte). |
| **AnnotatedImageView.java** | Vue custom qui dessine les boîtes de détection sur l'image. |

### Assets (`app/src/main/assets/`)

- `class_names.txt` : 15 classes COCO (person, car, etc.)
- `model_info.txt` : Spécifications du modèle YOLOv8
- `yolov8n.tflite` : Modèle TFLite (à générer via Colab, voir `/scripts/`)

### Configuration Build

- `build.gradle` : Dépendances (TensorFlow Lite, OkHttp, Gson)
- `AndroidManifest.xml` : Permissions (Internet, Audio, Vibration)

## Comment compiler

```bash
cd app-android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

Dans `MainActivity.java`, ligne ~42, modifier l'URL WebSocket :

```java
// Émulateur
private static final String ESP32_URL = "ws://10.0.2.2:8765";

// Téléphone physique
private static final String ESP32_URL = "ws://192.168.X.X:8765";
```

Remplacer `192.168.X.X` par l'IP du PC qui exécute `vision_server.py`.
