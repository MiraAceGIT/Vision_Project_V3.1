#!/usr/bin/env python3
"""
Script de validation — Test Vision Server

EN GROS : Vérifie que ton environnement est correctement configuré avant de lancer
le serveur vision_server.py. Teste les dépendances Python, la caméra, le modèle YOLO
et l'encodage WebSocket.

CE QUE ÇA TESTE :
1. Packages Python installés (OpenCV, ultralytics, websockets)
2. Modèle YOLOv8 chargeable
3. Caméra accessible
4. Inférence YOLO fonctionnelle
5. WebSocket disponible
6. Encodage Base64 des frames

USAGE :
    python test_vision_server.py
    
Si tous les tests passent, tu peux lancer vision_server.py en toute sécurité.
"""

import subprocess
import sys
import time

print("="*60)
print("🧪 TEST VISION SERVER")
print("="*60)

# Test 1: Python packages
print("\n1️⃣ Vérification des packages Python...")
packages = ['cv2', 'websockets', 'ultralytics', 'numpy']
missing = []

for pkg in packages:
    try:
        __import__(pkg)
        print(f"   ✅ {pkg}")
    except ImportError:
        print(f"   ❌ {pkg} MANQUANT")
        missing.append(pkg)

if missing:
    print(f"\n⚠️  Packages manquants: {', '.join(missing)}")
    print(f"   Installer avec: pip install {' '.join(missing)}")
    sys.exit(1)

print("\n✅ Tous les packages sont disponibles!")

# Test 2: YOLO model
print("\n2️⃣ Vérification du modèle YOLO...")
try:
    from ultralytics import YOLO
    print("   ⏳ Chargement du modèle...")
    model = YOLO('yolov8n.pt')
    print(f"   ✅ Modèle chargé")
    print(f"   📊 Classes: {len(model.names)}")
except Exception as e:
    print(f"   ❌ Erreur: {e}")
    sys.exit(1)

# Test 3: OpenCV camera
print("\n3️⃣ Vérification de la caméra...")
try:
    import cv2
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("   ❌ Caméra non accessible")
        print("   💡 Essayer avec CAMERA_ID = 1, 2, 3...")
        sys.exit(1)
    
    ret, frame = cap.read()
    if not ret:
        print("   ❌ Impossible de lire depuis la caméra")
        sys.exit(1)
    
    h, w = frame.shape[:2]
    print(f"   ✅ Caméra OK ({w}x{h})")
    cap.release()
except Exception as e:
    print(f"   ❌ Erreur: {e}")
    sys.exit(1)

# Test 4: YOLO inference
print("\n4️⃣ Test inférence YOLO...")
try:
    import cv2
    import numpy as np
    from ultralytics import YOLO
    
    model = YOLO('yolov8n.pt')
    
    # Créer une image test
    test_img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    
    print("   ⏳ Inférence (peut prendre 10-30 sec)...")
    import time
    start = time.time()
    results = model(test_img, verbose=False)
    elapsed = time.time() - start
    
    print(f"   ✅ Inférence OK ({elapsed:.2f}sec)")
    print(f"   📊 Détections: {len(results[0].boxes) if results[0].boxes else 0}")
except Exception as e:
    print(f"   ❌ Erreur: {e}")
    sys.exit(1)

# Test 5: WebSocket
print("\n5️⃣ Vérification WebSocket...")
try:
    import websockets
    print("   ✅ WebSocket disponible")
except ImportError:
    print("   ❌ WebSocket manquant")
    print("   💡 Installer: pip install websockets")
    sys.exit(1)

# Test 6: Base64
print("\n6️⃣ Test encodage Base64...")
try:
    import base64
    import cv2
    
    cap = cv2.VideoCapture(0)
    ret, frame = cap.read()
    cap.release()
    
    _, buffer = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
    b64 = base64.b64encode(buffer).decode()
    
    print(f"   ✅ Base64 OK ({len(b64)} chars)")
except Exception as e:
    print(f"   ❌ Erreur: {e}")
    sys.exit(1)

print("\n" + "="*60)
print("✅ TOUS LES TESTS PASSÉS!")
print("="*60)
print("\n🚀 Vous pouvez démarrer le serveur:")
print("   python vision_server.py")
print("   ou")
print("   python vision_server_minimal.py")
print("\n💡 N'oubliez pas:")
print("   1. Configurer l'IP dans MainActivity.java")
print("   2. Compiler l'app Android")
print("   3. Connecter le téléphone au même réseau")
