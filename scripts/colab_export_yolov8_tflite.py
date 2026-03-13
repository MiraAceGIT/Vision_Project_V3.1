"""
Export YOLOv8n vers TFLite — Script Google Colab

EN GROS : Exporte le modèle YOLOv8n en format TFLite pour Android.
C'est la SEULE méthode qui fonctionne sans installer Visual C++ sur Windows.

CE QUE ÇA FAIT :
1. Installe ultralytics dans Colab
2. Télécharge yolov8n.pt automatiquement
3. Exporte en yolov8n_float32.tflite (FP32, ~12 MB)
4. Télécharge le fichier sur ton PC

USAGE :
1. Ouvrir Google Colab : https://colab.research.google.com/
2. Nouveau notebook
3. Copier-coller ce code complet
4. Exécuter (Runtime → Run all)
5. Attendre 3-5 min
6. Télécharger le .tflite généré
7. Renommer en yolov8n.tflite
8. Placer dans app-android/app/src/main/assets/

NOTE : Export FP32 (pas INT8) pour éviter les dépendances C++/MSVC.
Latence Android : ~120ms (suffisant pour du temps réel).
"""

# ============================================================
# ÉTAPE 1: Installation des dépendances
# ============================================================
!pip install ultralytics --quiet

# ============================================================
# ÉTAPE 2: Télécharger YOLOv8n
# ============================================================
from ultralytics import YOLO
import os

print("📥 Téléchargement du modèle YOLOv8n...")
model = YOLO('yolov8n.pt')  # Télécharge automatiquement
print("✅ Modèle téléchargé")

# ============================================================
# ÉTAPE 3: Export TFLite (FP32 - sans quantization)
# ============================================================
print("\n🔄 Export TFLite en cours...")
print("⏱️  Cela prend ~3-5 minutes")

try:
    # Export sans quantization INT8 (pas besoin de Visual C++)
    export_path = model.export(
        format='tflite',
        imgsz=320,
        int8=False  # FP32 pour éviter les dépendances C++
    )
    
    print(f"\n✅ SUCCÈS!")
    print(f"📁 Fichier: {export_path}")
    
    # Vérifier la taille
    if os.path.exists(export_path):
        size_mb = os.path.getsize(export_path) / (1024*1024)
        print(f"📊 Taille: {size_mb:.2f} MB")
        
        # Télécharger automatiquement
        from google.colab import files
        files.download(export_path)
        print("\n⬇️  Téléchargement lancé! Vérifiez vos téléchargements.")
        
        print("\n" + "="*60)
        print("INSTRUCTIONS:")
        print("1. Le fichier 'yolov8n_float32.tflite' est téléchargé")
        print("2. Renommez-le en 'yolov8n.tflite'")
        print("3. Placez-le dans:")
        print("   app-android/app/src/main/assets/yolov8n.tflite")
        print("4. Rebuild l'APK Android")
        print("="*60)
    
except Exception as e:
    print(f"\n❌ ERREUR: {e}")
    import traceback
    traceback.print_exc()

# ============================================================
# OPTIONNEL: Export avec quantization INT8 (meilleure perf)
# ============================================================
print("\n" + "="*60)
print("OPTIONNEL: Export INT8 quantized (performances optimales)")
print("="*60)
print("Si vous voulez une version quantifiée (plus rapide sur Android):")
print("Décommentez le code ci-dessous:")
print()
print("# model_int8 = YOLO('yolov8n.pt')")
print("# export_int8 = model_int8.export(")
print("#     format='tflite',")
print("#     imgsz=320,")
print("#     int8=True,")
print("#     data='coco8.yaml'  # Dataset pour calibration")
print("# )")
print("# files.download(export_int8)")
