# Scripts — Utilitaires

## colab_export_yolov8_tflite.py

**Rôle** : Exporte YOLOv8n en format TFLite pour Android.

**Pourquoi Colab ?** : Sur Windows, l'export TFLite échoue à cause de dépendances C++/MSVC manquantes. Google Colab a tout préinstallé.

**Usage** :
1. Ouvrir https://colab.research.google.com/
2. Nouveau notebook
3. Copier-coller le contenu de ce fichier
4. Exécuter (Runtime → Run all)
5. Attendre 3-5 minutes
6. Télécharger `yolov8n_float32.tflite`
7. Renommer en `yolov8n.tflite`
8. Placer dans `app-android/app/src/main/assets/`

**Format** : FP32 (Float32), ~12 MB

**Latence** : ~120ms sur Android moyen (suffisant pour temps réel)
