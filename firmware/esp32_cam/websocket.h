/*
 * WebSocket Handler — Diffusion des frames
 * 
 * EN GROS : Gère le serveur WebSocket qui envoie les frames JPEG aux clients.
 * Encode les images en Base64, les wrappe en JSON, et les diffuse.
 * 
 * CE QUE ÇA FAIT :
 * - Serveur WebSocket asynchrone sur port 81
 * - Encodage Base64 des données JPEG
 * - Wrapping JSON avec métadonnées (timestamp, frame_id, taille)
 * - Tracking des clients connectés (max 5)
 * - Gestion des connexions/déconnexions
 * - Broadcast à tous les clients simultanément
 * 
 * FORMAT MESSAGE :
 * {
 *   "type": "frame",
 *   "image": "<base64 JPEG>",
 *   "timestamp": "2026-02-19T10:30:45",
 *   "frame_id": 42,
 *   "size": 18432
 * }
 */

#ifndef WEBSOCKET_H
#define WEBSOCKET_H

#include <ESPAsyncWebServer.h>
#include "ArduinoJson.h"
#include <mbedtls/base64.h>

// ============================================
// WebSocket Configuration
// ============================================
#define WS_PORT 81
#define MAX_CLIENTS 5
#define FRAME_TIMEOUT_MS 5000

// ============================================
// WebSocket Handler Class
// ============================================
class WebSocketHandler {
private:
  AsyncWebSocket* ws;
  unsigned long lastFrameTime;
  int activeClients;
  
public:
  /**
   * Constructeur du gestionnaire WebSocket
   * 
   * @param websocket Pointeur vers l'instance AsyncWebSocket à gérer
   */
  WebSocketHandler(AsyncWebSocket* websocket) 
    : ws(websocket), lastFrameTime(0), activeClients(0) {}
  
  // ========================================
  // Setup Handler
  // ========================================
  /**
   * Configure le handler d'événements WebSocket
   * 
   * Enregistre le callback pour gérer :
   * - Connexions clients
   * - Déconnexions
   * - Messages entrants
   * - Pongs (keep-alive)
   * - Erreurs
   */
  void setup() {
    // Connection handler
    ws->onEvent([this](AsyncWebSocket* server, 
                       AsyncWebSocketClient* client,
                       AwsEventType type,
                       void* arg,
                       uint8_t* data,
                       size_t len) {
      handleWebSocketEvent(server, client, type, arg, data, len);
    });
  }
  
  // ========================================
  // Event Handler
  // ========================================
  /**
   * Gestionnaire principal des événements WebSocket
   * 
   * Dispatche les événements selon leur type :
   * - WS_EVT_CONNECT : nouveau client
   * - WS_EVT_DISCONNECT : client déconnecté
   * - WS_EVT_DATA : message reçu
   * - WS_EVT_PONG : réponse ping
   * - WS_EVT_ERROR : erreur de connexion
   * 
   * @param server Instance du serveur WebSocket
   * @param client Client concerné par l'événement
   * @param type Type d'événement WebSocket
   * @param arg Argument optionnel (dépend du type)
   * @param data Données du message (si applicable)
   * @param len Longueur des données
   */
  void handleWebSocketEvent(AsyncWebSocket* server,
                           AsyncWebSocketClient* client,
                           AwsEventType type,
                           void* arg,
                           uint8_t* data,
                           size_t len) {
    switch(type) {
      case WS_EVT_CONNECT:
        handleClientConnect(client);
        break;
        
      case WS_EVT_DISCONNECT:
        handleClientDisconnect(client);
        break;
        
      case WS_EVT_DATA:
        handleClientMessage(client, data, len);
        break;
        
      case WS_EVT_PONG:
        handleClientPong(client);
        break;
        
      case WS_EVT_ERROR:
        Serial.printf("❌ WebSocket error: %s\n", (char*)data);
        break;
    }
  }
  
  // ========================================
  // Client Connection Handler
  // ========================================
  /**
   * Traite la connexion d'un nouveau client WebSocket
   * 
   * Incrémente le compteur de clients actifs et affiche
   * l'ID du client et le nombre total de connexions.
   * Envoie un message de bienvenue au client.
   * 
   * @param client Pointeur vers le client nouvellement connecté
   */
  void handleClientConnect(AsyncWebSocketClient* client) {
    activeClients++;
    Serial.printf("✓ WebSocket client connected (ID: %u)\n", client->id());
    Serial.printf("  Active clients: %d\n", activeClients);
    
    // Envoyer le message de bienvenue
    sendJsonMessage(client, "connection", "Client connected to PROJET_VISION_RELEASE1");
  }
  
  // ========================================
  // Client Disconnection Handler
  // ========================================
  /**
   * Traite la déconnexion d'un client WebSocket
   * 
   * Décrémente le compteur de clients actifs et affiche
   * l'ID du client déconnecté.
   * 
   * @param client Pointeur vers le client déconnecté
   */
  void handleClientDisconnect(AsyncWebSocketClient* client) {
    activeClients = max(0, activeClients - 1);
    Serial.printf("✓ WebSocket client disconnected (ID: %u)\n", client->id());
    Serial.printf("  Active clients: %d\n", activeClients);
  }
  
  // ========================================
  // Client Message Handler
  // ========================================
  /**
   * Traite les messages JSON reçus des clients
   * 
   * Commandes supportées :
   * - "ping" : répond "pong"
   * - "status" : envoie les statistiques système
   * - "reset" : acknowledge la commande de reset
   * 
   * @param client Client ayant envoyé le message
   * @param data Buffer contenant le JSON
   * @param len Longueur du message
   */
  void handleClientMessage(AsyncWebSocketClient* client, 
                          uint8_t* data, 
                          size_t len) {
    // Parser la commande JSON
    StaticJsonDocument<256> doc;
    DeserializationError error = deserializeJson(doc, data, len);
    
    if (error) {
      Serial.printf("⚠ JSON parse error: %s\n", error.c_str());
      return;
    }
    
    const char* cmd = doc["cmd"] | "";
    
    if (strcmp(cmd, "ping") == 0) {
      sendJsonMessage(client, "pong", "Pong!");
    }
    else if (strcmp(cmd, "status") == 0) {
      sendSystemStatus(client);
    }
    else if (strcmp(cmd, "reset") == 0) {
      sendJsonMessage(client, "info", "Reset command received");
    }
  }
  
  // ========================================
  // Client Pong Handler
  // ========================================
  /**
   * Traite la réponse pong d'un client (keep-alive)
   * 
   * Calcule la latence réseau. Affiche un warning si
   * la latence dépasse 500ms.
   * 
   * @param client Client ayant répondu au ping
   */
  void handleClientPong(AsyncWebSocketClient* client) {
    // Mesure de latence
    unsigned long latency = millis() - client->lastMessageTime();
    if (latency > 500) {
      Serial.printf("⚠ High latency client %u: %lu ms\n", client->id(), latency);
    }
  }
  
  // ========================================
  // Broadcast Frame to All Clients
  // ========================================
  /**
   * Diffuse une frame JPEG à tous les clients connectés
   * 
   * Étapes :
   * 1. Encode le buffer JPEG en Base64
   * 2. Construit le JSON avec métadonnées
   * 3. Envoie à tous les clients actifs
   * 4. Libère la mémoire temporaire
   * 
   * Format JSON :
   * {
   *   "type": "frame",
   *   "jpeg_base64": "<base64>",
   *   "frame_num": 42,
   *   "timestamp": 123456,
   *   "size": 18432
   * }
   * 
   * @param fb Frame buffer JPEG de la caméra
   * @param metadata Document JSON contenant les métadonnées
   */
  void broadcastFrame(camera_fb_t* fb, JsonDocument& metadata) {
    if (!fb || activeClients == 0) {
      return;
    }
    
    // Allouer un buffer pour la frame encodée en Base64
    size_t encodedSize = (fb->len * 4) / 3 + 10;
    uint8_t* encoded = (uint8_t*)malloc(encodedSize);
    
    if (!encoded) {
      Serial.println("❌ Memory allocation failed for Base64 encoding");
      return;
    }
    
    // Encoder en Base64
    size_t outlen = 0;
    int ret = mbedtls_base64_encode(encoded, encodedSize, &outlen, 
                                    fb->buf, fb->len);
    
    if (ret != 0) {
      Serial.printf("❌ Base64 encoding failed: %d\n", ret);
      free(encoded);
      return;
    }
    
    // Créer le payload JSON
    StaticJsonDocument<512> payload;
    payload["type"] = "frame";
    payload["jpeg_base64"] = String((const char*)encoded, outlen);
    
    // Ajouter les métadonnées
    payload["frame_num"] = metadata["frame_num"];
    payload["timestamp"] = metadata["timestamp"];
    payload["size"] = metadata["size"];
    
    // Sérialiser et envoyer
    String json;
    serializeJson(payload, json);
    
    ws->textAll(json);
    
    // Nettoyage
    free(encoded);
    lastFrameTime = millis();
  }
  
  // ========================================
  // Send JSON Message to Client
  // ========================================
  /**
   * Envoie un message JSON simple à un client spécifique
   * 
   * @param client Client destinataire
   * @param type Type du message (ex: "info", "error", "pong")
   * @param message Contenu du message
   */
  void sendJsonMessage(AsyncWebSocketClient* client,
                      const char* type,
                      const char* message) {
    StaticJsonDocument<256> doc;
    doc["type"] = type;
    doc["message"] = message;
    doc["timestamp"] = millis();
    
    String json;
    serializeJson(doc, json);
    
    client->text(json);
  }
  
  // ========================================
  // Send System Status to Client
  // ========================================
  /**
   * Envoie les statistiques système à un client
   * 
   * Informations envoyées :
   * - FPS : 3
   * - Résolution : QVGA 320x240
   * - Qualité JPEG : 10
   * - Clients actifs
   * - Uptime (millisecondes)
   * - Heap libre
   * 
   * @param client Client demandant le status
   */
  void sendSystemStatus(AsyncWebSocketClient* client) {
    StaticJsonDocument<256> doc;
    doc["type"] = "status";
    doc["fps"] = 3;
    doc["resolution"] = "QVGA (320x240)";
    doc["jpeg_quality"] = 10;
    doc["active_clients"] = activeClients;
    doc["uptime_ms"] = millis();
    doc["free_heap"] = ESP.getFreeHeap();
    
    String json;
    serializeJson(doc, json);
    
    client->text(json);
  }
  
  // ========================================
  // Handle Connections (Call in main loop)
  // ========================================
  /**
   * Maintient les connexions WebSocket actives
   * 
   * Vérifie si les clients sont toujours vivants.
   * Envoie un ping si aucune frame n'a été envoyée
   * depuis FRAME_TIMEOUT_MS (5 secondes).
   * 
   * À appeler périodiquement dans loop().
   */
  void handleConnections() {
    // Vérifier les connexions périmées
    if (millis() - lastFrameTime > FRAME_TIMEOUT_MS && activeClients > 0) {
      // Optionnellement envoyer un ping pour détecter les clients morts
      broadcastPingMessage();
    }
  }
  
  // ========================================
  // Broadcast Ping Message
  // ========================================
  /**
   * Envoie un ping à tous les clients (keep-alive)
   * 
   * Message JSON :
   * {
   *   "type": "ping",
   *   "timestamp": <millis>
   * }
   */
  void broadcastPingMessage() {
    StaticJsonDocument<128> doc;
    doc["type"] = "ping";
    doc["timestamp"] = millis();
    
    String json;
    serializeJson(doc, json);
    
    ws->textAll(json);
  }
  
  // ========================================
  // Get Statistics
  // ========================================
  /**
   * Retourne le nombre de clients WebSocket actuellement connectés
   * 
   * @return Nombre de clients actifs
   */
  int getActiveClients() const {
    return activeClients;
  }
  
  /**
   * Retourne le timestamp de la dernière frame diffusée
   * 
   * @return Temps en millisecondes depuis le démarrage (millis())
   */
  unsigned long getLastFrameTime() const {
    return lastFrameTime;
  }
};

#endif // WEBSOCKET_H
