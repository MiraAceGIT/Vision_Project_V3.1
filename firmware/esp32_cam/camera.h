/*
 * Camera Header — Configuration OV2640
 * 
 * EN GROS : Définit les pins et paramètres de la caméra ESP32-CAM.
 * Résolution QVGA (320×240), compression JPEG agressive (qualité 10).
 * 
 * CE QUE ÇA CONFIGURE :
 * - Pins GPIO pour l'ESP32-CAM AI Thinker (standard)
 * - Résolution QVGA (320×240) : optimal pour YOLO 320×320
 * - Qualité JPEG 10 : compression 4:1, ~18 KB par frame
 * - Single frame buffer : économie de RAM
 * - XCLK 20 MHz : fréquence horloge caméra
 * 
 * TEMPS DE CAPTURE : 50-80ms par frame
 * 
 * NOTE : Ne pas modifier les pins sauf si tu as un autre modèle d'ESP32-CAM
 */

#ifndef CAMERA_H
#define CAMERA_H

#include "esp_camera.h"

// ============================================
// Camera Model Configuration
// ============================================
#define CAMERA_MODEL_AI_THINKER  // AI Thinker ESP32-CAM

// Pin definitions for AI Thinker ESP32-CAM
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26  // I2C SDA (données)
#define SIOC_GPIO_NUM     27  // I2C SCL (horloge)

#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

// ============================================
// Resolution: QVGA (320x240)
// ============================================
// Sélectionné pour un équilibre optimal :
// - Assez petit pour la transmission WebSocket (~15-20 KB)
// - Assez grand pour l'inférence YOLO (le modèle attend 320×320)
// - Temps de capture : 50-80ms
// - Ratio de compression : 4:1 (typique)

// ============================================
// JPEG Compression: Quality 10
// ============================================
// Justification de la compression agressive :
// - Qualité 12 (ESSAI2T1) : 20-25 KB → Trop lourd pour latence <1s
// - Qualité 10 (ESSAI2T2) : 15-20 KB ← OPTIMAL
// - Qualité 8 : <15 KB mais dégradation de qualité inacceptable
// - WiFi 2.4 GHz typique : 15-20 Mbps → 15 KB = ~10ms transmission

// ============================================
// Function: Initialize Camera
// ============================================
/**
 * Initialise la caméra OV2640 avec configuration optimale YOLO
 * 
 * Configuration :
 * - Pins AI Thinker ESP32-CAM (standard)
 * - Résolution : QVGA 320x240
 * - Format : JPEG qualité 10 (compression agressive)
 * - Clock : 20 MHz
 * - Buffer : simple (économie RAM)
 * - Auto-exposition activée
 * - Correction lentille activée
 * - Balance des blancs automatique
 * 
 * @return true si initialisation réussie, false en cas d'erreur
 */
bool initializeCamera() {
  camera_config_t config;
  
  // Attribution des pins
  config.pin_pwdn       = PWDN_GPIO_NUM;
  config.pin_reset      = RESET_GPIO_NUM;
  config.pin_xclk       = XCLK_GPIO_NUM;
  config.pin_sda        = SIOD_GPIO_NUM;
  config.pin_scl        = SIOC_GPIO_NUM;
  
  // Pins de données
  config.pin_d7         = Y9_GPIO_NUM;
  config.pin_d6         = Y8_GPIO_NUM;
  config.pin_d5         = Y7_GPIO_NUM;
  config.pin_d4         = Y6_GPIO_NUM;
  config.pin_d3         = Y5_GPIO_NUM;
  config.pin_d2         = Y4_GPIO_NUM;
  config.pin_d1         = Y3_GPIO_NUM;
  config.pin_d0         = Y2_GPIO_NUM;
  config.pin_vsync      = VSYNC_GPIO_NUM;
  config.pin_href       = HREF_GPIO_NUM;
  config.pin_pclk       = PCLK_GPIO_NUM;
  
  // Horloge caméra : 20 MHz (optimal pour YOLO + 3 Hz frame rate)
  config.xclk_freq_hz   = 20000000;
  config.ledc_timer     = LEDC_TIMER_0;
  config.ledc_channel   = LEDC_CHANNEL_0;
  
  // Paramètres de frame
  config.pixel_format   = PIXFORMAT_JPEG;
  config.frame_size     = FRAMESIZE_QVGA;    // 320x240
  config.jpeg_quality   = 10;                 // Aggressive compression
  config.fb_count       = 1;                  // Single buffer
  config.fb_location    = CAMERA_FB_IN_PSRAM; // Use PSRAM if available
  config.grab_mode      = CAMERA_GRAB_LATEST; // Drop old frames
  
  // Paramètres du capteur caméra (optimisé pour YOLO)
  config.gain_ceil      = 6;      // Gain réduit pour meilleur focus
  config.brightness     = 0;      // Neutre
  config.saturation     = 0;      // Neutre
  config.contrast       = 0;      // Neutre
  config.ae_level       = 0;      // Auto exposition à 0
  
  // Initialiser le driver de caméra
  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("❌ Camera init error: 0x%x\n", err);
    return false;
  }
  
  // Configurer le capteur
  sensor_t* s = esp_camera_sensor_get();
  if (!s) {
    Serial.println("❌ Camera sensor not found");
    return false;
  }
  
  // Optimisations OV2640 pour YOLO
  s->set_framesize(s, FRAMESIZE_QVGA);        // 320x240
  s->set_pixformat(s, PIXFORMAT_JPEG);
  s->set_quality(s, 10);                       // JPEG quality 10
  
  // Contrôle d'exposition pour luminosité constante
  s->set_exposure_ctrl(s, 1);                 // Activer l'auto-exposition
  s->set_ae_level(s, 0);                      // Niveau d'auto-exposition à 0
  
  // Désactiver les effets spéciaux (pour la précision YOLO)
  s->set_special_effect(s, 0);                // Aucun effet spécial
  s->set_whitebal(s, 1);                      // Activer la balance des blancs
  s->set_awb_gain(s, 1);                      // Activer le gain AWB
  
  // Correction de lentille pour meilleure détection des contours
  s->set_lenc(s, 1);                          // Activer la correction de lentille
  s->set_dcw(s, 1);                           // Activer DCW
  
  return true;
}

// ============================================
// Function: Capture Single Frame
// ============================================
/**
 * Capture une frame depuis la caméra
 * 
 * Obtient un buffer JPEG de la caméra OV2640.
 * Le buffer doit être libéré avec releaseFrame() après usage.
 * 
 * @return Pointeur vers le frame buffer, ou nullptr en cas d'échec
 */
camera_fb_t* captureFrame() {
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("❌ Frame capture failed");
    return nullptr;
  }
  return fb;
}

// ============================================
// Function: Release Frame Buffer
// ============================================
/**
 * Libère le buffer d'une frame capturée
 * 
 * Retourne le buffer à la caméra pour réutilisation.
 * Appeler TOUJOURS après avoir fini d'utiliser une frame.
 * 
 * @param fb Pointeur vers le frame buffer à libérer
 */
void releaseFrame(camera_fb_t* fb) {
  if (fb) {
    esp_camera_fb_return(fb);
  }
}

// ============================================
// Frame Size & Quality Information
// ============================================
struct FrameInfo {
  const char* resolution;
  int width;
  int height;
  int typical_size_kb;  // Taille compressée typique
  int capture_time_ms;   // Temps de capture typique
};

FrameInfo getFrameInfo() {
  return {
    .resolution = "QVGA",
    .width = 320,
    .height = 240,
    .typical_size_kb = 18,  // Plage 15-20 KB
    .capture_time_ms = 65   // Typique : 50-80ms
  };
}

#endif // CAMERA_H
