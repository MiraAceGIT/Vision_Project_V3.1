/*
 * Firmware ESP32-CAM — Capture et diffusion vidéo
 * 
 * EN GROS : Code qui tourne sur l'ESP32-CAM. Capture des images QVGA 320×240
 * à 3 Hz, les compresse en JPEG, les encode en Base64, et les envoie via
 * WebSocket à l'application Android.
 * 
 * CE QUE ÇA FAIT :
 * - Connexion WiFi au réseau local
 * - Initialisation caméra OV2640 (QVGA, qualité JPEG 10)
 * - Boucle de capture à 3 Hz (330ms entre frames)
 * - Compression JPEG (~18 KB par frame)
 * - Encodage Base64 pour transport WebSocket
 * - Diffusion JSON :
 *   {"type": "frame", "image": "<base64>", "timestamp": "...", "frame_id": 42}
 * - Gestion multi-clients (max 5)
 * - Statistiques temps réel (capture, transmission, FPS)
 * 
 * LATENCE CIBLE : <80ms capture + <100ms transmission = <200ms total
 * 
 * CONFIGURATION REQUISE :
 * - Modifier SSID et PASSWORD (lignes 22-23)
 * - Compiler avec Arduino IDE pour ESP32
 * - Flasher sur ESP32-CAM AI Thinker
 */

#include <Arduino.h>
#include <WiFi.h>
#include <ESPAsyncWebServer.h>
#include "esp_camera.h"
#include "ArduinoJson.h"
#include "camera.h"
#include "websocket.h"

// ============================================
// WiFi Configuration
// ============================================
const char* SSID = "your_ssid_here";           // ← À configurer
const char* PASSWORD = "your_password_here";   // ← À configurer

// ============================================
// Server Configuration
// ============================================
AsyncWebServer server(80);
AsyncWebSocket ws("/ws");
WebSocketHandler wsHandler(&ws);

// ============================================
// Frame Rate Configuration
// ============================================
const int FRAME_RATE_MS = 330;  // 3 Hz = 330 ms entre les frames
unsigned long lastFrameTime = 0;
int frameCount = 0;

// ============================================
// Camera & Capture Buffers
// ============================================
camera_fb_t* fb = nullptr;
static const int FRAME_QUEUE_SIZE = 2;
volatile int queuedFrames = 0;

// ============================================
// Performance Metrics
// ============================================
unsigned long captureStartTime = 0;
unsigned long transmitStartTime = 0;
unsigned long totalLatency = 0;

// ============================================
// Setup Initialization
// ============================================
/**
 * Initialisation du système ESP32-CAM
 * 
 * Séquence d'initialisation :
 * 1. Démarrage série (115200 bauds)
 * 2. Initialisation caméra OV2640 (QVGA, JPEG Q10)
 * 3. Connexion WiFi (SSID/PASSWORD configurés)
 * 4. Démarrage serveur HTTP port 80
 * 5. Configuration handler WebSocket
 * 6. Affichage des statistiques système
 * 
 * Bloque indéfiniment en cas d'échec caméra ou WiFi.
 */
void setup() {
  Serial.begin(115200);
  delay(500);
  
  printStartupBanner();
  
  // Initialize camera
  if (!initializeCamera()) {
    Serial.println("❌ Camera initialization FAILED");
    while (true) delay(1000);
  }
  Serial.println("✓ Camera initialized (QVGA, 3 Hz, YOLO optimized)");
  
  // Connect to WiFi
  if (!connectToWiFi()) {
    Serial.println("❌ WiFi connection FAILED");
    while (true) delay(1000);
  }
  
  // Start web server
  setupWebServer();
  server.begin();
  Serial.println("✓ Web server started on port 80");
  
  // Setup WebSocket
  wsHandler.setup();
  Serial.println("✓ WebSocket handler configured");
  
  printSystemStatus();
  lastFrameTime = millis();
}

// ============================================
// Main Loop - Frame Capture & Broadcast
// ============================================
/**
 * Boucle principale de capture et diffusion
 * 
 * Contrôle le frame rate à 3 Hz (330ms entre frames).
 * Capture une frame, l'encode en Base64, et la diffuse
 * via WebSocket à tous les clients connectés.
 * 
 * Affiche des statistiques toutes les 30 frames (10 sec).
 * Délai de 10ms pour éviter les watchdog timeouts.
 */
void loop() {
  unsigned long currentTime = millis();
  
  // Contrôle du frame rate : capture toutes les FRAME_RATE_MS millisecondes
  if ((currentTime - lastFrameTime) >= FRAME_RATE_MS) {
    captureAndBroadcastFrame();
    lastFrameTime = currentTime;
    frameCount++;
    
    // Afficher les statistiques toutes les 30 frames (10 secondes à 3 Hz)
    if (frameCount % 30 == 0) {
      printFrameStatistics();
    }
  }
  
  // Gérer les communications WebSocket
  wsHandler.handleConnections();
  
  // Petit délai pour éviter les problèmes de watchdog
  delay(10);
}

// ============================================
// Frame Capture & Broadcast Implementation
// ============================================
/**
 * Capture une frame caméra et la diffuse aux clients WebSocket
 * 
 * Étapes :
 * 1. Capture JPEG depuis la caméra OV2640
 * 2. Crée métadonnées JSON (frame_num, timestamp, taille)
 * 3. Encode en Base64 et diffuse via WebSocket
 * 4. Calcule la latence totale (capture + transmission)
 * 5. Libère le buffer caméra
 * 
 * Affiche un warning si la latence dépasse 200ms.
 */
void captureAndBroadcastFrame() {
  captureStartTime = millis();
  
  // 1. Capturer une frame depuis la caméra
  fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("❌ Camera capture failed");
    return;
  }
  
  unsigned long captureTime = millis() - captureStartTime;
  
  // 2. Créer le payload JSON avec les métadonnées
  StaticJsonDocument<256> doc;
  doc["frame_num"] = frameCount;
  doc["timestamp"] = captureStartTime;
  doc["capture_ms"] = captureTime;
  doc["size"] = fb->len;
  
  // 3. Diffuser la frame avec encodage Base64
  transmitStartTime = millis();
  wsHandler.broadcastFrame(fb, doc);
  unsigned long transmitTime = millis() - transmitStartTime;
  
  // 4. Calculer la latence totale
  totalLatency = millis() - captureStartTime;
  
  // Debug : logger les frames lentes (>200ms)
  if (totalLatency > 200) {
    Serial.printf("⚠ Slow frame #%d: %lu ms (capture: %lu ms, transmit: %lu ms)\n", 
                  frameCount, totalLatency, captureTime, transmitTime);
  }
  
  // Libérer le buffer de frame
  esp_camera_fb_return(fb);
}

// ============================================
// WiFi Connection Handler
// ============================================
/**
 * Connexion au réseau WiFi configuré
 * 
 * Tente de se connecter au SSID/PASSWORD définis.
 * Timeout après 20 tentatives (10 secondes).
 * 
 * Affiche l'IP locale et la force du signal (RSSI) en cas de succès.
 * 
 * @return true si connexion réussie, false en cas de timeout
 */
bool connectToWiFi() {
  Serial.print("🔄 Connecting to WiFi: ");
  Serial.println(SSID);
  
  WiFi.mode(WIFI_STA);
  WiFi.begin(SSID, PASSWORD);
  
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 20) {
    delay(500);
    Serial.print(".");
    attempts++;
  }
  
  if (WiFi.status() != WL_CONNECTED) {
    return false;
  }
  
  Serial.println("\n✓ WiFi connected!");
  Serial.print("  IP: ");
  Serial.println(WiFi.localIP());
  Serial.print("  Signal strength: ");
  Serial.print(WiFi.RSSI());
  Serial.println(" dBm");
  
  return true;
}

// ============================================
// Web Server Setup
// ============================================
/**
 * Configure les endpoints du serveur HTTP
 * 
 * Endpoints :
 * - GET /health : statut système (frame_count, uptime, WiFi RSSI)
 * - GET /status : métriques détaillées (FPS, latence, résolution)
 * - GET / : page d'accueil texte
 * - WebSocket /ws : diffusion des frames
 */
void setupWebServer() {
  // Health check endpoint
  server.on("/health", HTTP_GET, [](AsyncWebServerRequest* request) {
    AsyncJsonResponse* response = new AsyncJsonResponse();
    response->setLength();
    JsonObject root = response->getRoot();
    root["status"] = "ok";
    root["frame_count"] = frameCount;
    root["uptime_sec"] = millis() / 1000;
    root["wifi_rssi"] = WiFi.RSSI();
    request->send(response);
  });
  
  // Status endpoint with metrics
  server.on("/status", HTTP_GET, [](AsyncWebServerRequest* request) {
    AsyncJsonResponse* response = new AsyncJsonResponse();
    response->setLength();
    JsonObject root = response->getRoot();
    root["status"] = "streaming";
    root["fps"] = 3;
    root["frame_rate_ms"] = FRAME_RATE_MS;
    root["total_frames"] = frameCount;
    root["last_latency_ms"] = totalLatency;
    root["camera_resolution"] = "QVGA (320x240)";
    root["compression"] = "JPEG Quality 10";
    request->send(response);
  });
  
  // WebSocket endpoint
  server.addHandler(&ws);
  
  // Root redirect
  server.on("/", HTTP_GET, [](AsyncWebServerRequest* request) {
    request->send(200, "text/plain", "PROJET_VISION_RELEASE1 - ESP32-CAM WebSocket Server");
  });
}

// ============================================
// Diagnostic & Status Functions
// ============================================
/**
 * Affiche la bannière de démarrage sur le port série
 * 
 * Affiche le nom du projet, la version du firmware
 * et le type de système (ESP32-CAM).
 */
void printStartupBanner() {
  Serial.println("\n========================================");
  Serial.println("PROJET_VISION_RELEASE1");
  Serial.println("Blind Helmet Vision System - Release 1");
  Serial.println("ESP32-CAM Firmware v1.0");
  Serial.println("========================================\n");
}

/**
 * Affiche l'état complet du système sur le port série
 * 
 * Informations affichées :
 * - WiFi : SSID et adresse IP locale
 * - Caméra : résolution (QVGA) et qualité JPEG (10)
 * - Frame rate : 3 Hz
 * - Serveur : port 80
 * - WebSocket : URL complète (ws://IP/ws)
 */
void printSystemStatus() {
  Serial.println("\n========== SYSTEM STATUS ==========");
  Serial.printf("WiFi: %s (IP: %s)\n", 
                WiFi.SSID().c_str(), 
                WiFi.localIP().toString().c_str());
  Serial.printf("Camera: QVGA (320x240), JPEG Quality 10\n");
  Serial.printf("Frame Rate: 3 Hz (%d ms interval)\n", FRAME_RATE_MS);
  Serial.printf("Server: Running on port 80\n");
  Serial.printf("WebSocket: ws://%s/ws\n", WiFi.localIP().toString().c_str());
  Serial.println("===================================\n");
}

/**
 * Affiche les statistiques de capture périodiques
 * 
 * Calcule et affiche :
 * - Numéro de frame actuel
 * - Temps de fonctionnement (secondes)
 * - FPS réel (frames / uptime)
 * - Latence de la dernière frame (ms)
 */
void printFrameStatistics() {
  unsigned long uptime = millis() / 1000;
  float actualFps = (float)frameCount / (uptime > 0 ? uptime : 1);
  
  Serial.printf("📊 Frame #%d | Uptime: %lu s | FPS: %.2f | Last Latency: %lu ms\n",
                frameCount, uptime, actualFps, totalLatency);
}

// ============================================
// Memory & Debug Utilities
// ============================================
/**
 * Affiche les statistiques mémoire et CPU
 * 
 * Informations :
 * - Heap utilisé / total (bytes)
 * - Heap libre (bytes)
 * - Fréquence CPU (MHz)
 */
void printMemoryStats() {
  Serial.printf("📦 Heap: %d / %d bytes (Free: %d)\n",
                ESP.getHeapSize() - ESP.getFreeHeap(),
                ESP.getHeapSize(),
                ESP.getFreeHeap());
  Serial.printf("⚡ CPU Freq: %u MHz\n", ESP.getCpuFreqMHz());
}
