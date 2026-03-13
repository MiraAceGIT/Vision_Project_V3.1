# Rapport — Partie Logicielle & Intelligence Artificielle
### Projet TIPE 

# PARTIE A LIRE ABSOLUMENT (PLA) !!!!!!


> ⚠️ **Note importante pour la lecture de ce rapport**
> Ce rapport décrit **uniquement la partie logicielle et IA** du projet.
> La partie capteurs ultrasons / retour haptique, est un système **totalement indépendant** : elles fonctionnent en duo.
> À ce stade, **il n'existe aucun lien, aucune intégration et aucune communication** entre les deux parties. Elles fonctionnent en parallèle, sans échange de données (POURRA INTEGRER A LAPP ? COMME TU VEUX). Cette situation est volontaire et clairement assumée dans ce rapport.

Le rapport a été entièrement relu et corrigé, les parties "raw" cad les parties non modifiées avec la réponse donnée directement seront ouverte par ''''''''i'''''''' (approximativement pr le nombre de ') et les parties moins principales ''''''''''mi'''''''''', les deux fermées par ''''''''''''''.


Hierarchie : 400 premieres pages (3 premieres parties) importantes sauf contre indication . Les shémas sont assez bien foutus : ils permettent une bonne vision globale. Noter cependant qu'ils n'integrent pas les fonctions et parties text/verification comme la bonne connexion au serveur, la bonne reception de certains fichiers, etc.
ensuite (partie 4), les étapes sont importantes (regarder "Ce que j'ai fait? " et "Questions posées") mais les sous etapes le sont moins (a survoler en premier lieu)



---

## Table des matières

1. [Vue d'ensemble du projet](#1-vue-densemble)
2. [Schéma des flux de données](#2-schéma-des-flux)
3. [Architecture du code](#3-architecture-du-code)
4. [Journal de conception chronologique](#4-journal-de-conception)
5. [Ce qui manque encore](#5-ce-qui-manque-encore)

---

## 1. Vue d'ensemble

### Le rôle de ma partie dans le projet global

Le projet vise à aider une personne malvoyante à se déplacer en sécurité. Il se compose de deux parties complémentaires :


| **Capteurs ultrasons + retour haptique**  Détecter qu'il y a un obstacle physique et en informer l'utilisateur par vibration |
| **Caméra + IA + Application mobile**  Identifier **ce que c'est** (voiture, personne, feu rouge...) et l'annoncer vocalement |

1e partie elec répond à la question : **"Est-ce qu'il y a quelque chose, loin ou pas ?"**
2e partie ia partie répond à la question : **"Qu'est-ce que c'est, et où est-ce ?"**


Les deux systèmes sont **complémentaires mais indépendants**. Chacun fonctionne sans l'autre. (ah bon ?)

### Comment les deux parties POURRAIENT se connecter plus tard

Dans une version future, les deux systèmes pourraient communiquer :



'''''''''''i'''''''''''


- La partie IA pourrait envoyer à l'ESP32 une commande de vibration renforcée lorsqu'un objet dangereux est détecté (ex : voiture à grande vitesse).
- L'ESP32 pourrait signaler à l'application qu'un obstacle très proche a été détecté, pour déclencher une alerte vocale supplémentaire.

**Mais ce n'est pas implémenté aujourd'hui.** Ce serait une évolution future nécessitant un protocole de communication commun défini entre les deux parties.


'''''''''''''''''''''



- Une communication entre l'esp et l'application peut aussi etre possible (sorte de simulation de buzzer mais via oreillette )
---








## 2. Schéma des flux de données

> Les références **(cf. X)** renvoient au [DOCUMENT_ANNEXE.md](DOCUMENT_ANNEXE.md) qui contient les extraits de code correspondants.

### Architecture actuelle (mode démonstration sur PC)

```
┌─────────────┐   images brutes (actu 30fps)───────────────────────────┐
│   WEBCAM PC │ ─────────────────────▶│       vision_server.py        │
│  (OpenCV)   │                        │  Pré : OpenCV to Yolo (tableau numpy qui contient les                                      pixels de l'image)                            │
                                       │  1. YOLOv8 analyse l'image (retourne liste de detections, en gros
                                       │     en interne c'est un reseau de neurone qui detecte des zones
                                       │     "suspectes" les classe (genre personne, voiture etc)
                                       │     puis retourne les box de detections, la classe, et la confiance)
                                       │  2. Calcule les alertes (groupes, vitesse, feux pieton, meteo, reste a venir) (cf. 1)
                                       │  3. Encode en JSON (objet python to chaine de texte) (cf. 2)
                                       │  4. Envoie via WebSocket (envoie du texte via TCP/IP 8 fois/seconde,
                                       │     c'est en gros ce qui keep la co PC-Android) (cf. 3)
                                       └───────────────┬───────────────┘
                                                       │ JSON via WebSocket
                                                       │ (ws://10.0.2.2:8765) 
                                                       ▼
                                       ┌───────────────────────────────┐
                                       │     Application Android       │
                                       │                               │
                                       │1RecoitlefichierJSON                                          
                                       │  2. Decode JSON en objets Java utilisables(cf4)              │
                                       │  3. Affiche les boites colorees selon la confiance(cf5)      │
                                       │  4. Annonce en voix francaise via TTS(cf6)                   
                                       └───────────────────────────────┘
                                                       │
                                                       ▼
                                                Voix française (des fois dans le code c'est déclaré en anglais puis en francais (cf classes) dev's bad ig)
                                        "Personne à gauche" (cf. 6)
                                               │ Bluetooth
                                               ▼
                                          Oreillette 🎧 on connecte en blutooth car c'est fiable et permet d'utiliser des écouteurs non filaires communs et aujourd'hui peu cher (non aux monopoles!)
```

### Architecture cible (projet final sans PC)

```
┌──────────────┐  images via WiFi (stream MJPEG)    ┌──────────────────────────────────────────────┐
│  ESP32-CAM   │ ─────────────────────────────────▶ │         Téléphone Android                    │
│ (sur casque) │                                    │                                              │
└──────────────┘                                    │  1. Recoit le stream caméra                  │
                                                    │  2. YOLOv8 .tflite tourne directement ici    │
                                                    │     (meme modele mais converti pour mobile,  │
                                                    │     cf. 7 pour la difference pt vs tflite)   │
                                                    │  3. Calcule les alertes (meme logique qu'actuellement mais plus fourni evidemment)│
                                                    │  4. Annonce en voix francaise via TTS (cf. 6) 
                                                    │on garde ce fonctionnement par simplicité, peut etre amélioré par la suite (UI/UX work)
                                                    └──────────────────────┬───────────────────────┘
                                                                           │ Bluetooth
                                                                           ▼
                                                                    Oreillette 🎧
                                                                 "Voiture à droite"
```








## 3. Architecture du code

### Structure des dossiers



''''''''i'''''''''''


```
PROJET_VISION_RELEASE1/
│
├── vision_server.py          ← Serveur Python (cerveau de la détection)
├── yolov8n.pt                ← Fichier du modèle YOLOv8 pré-entraîné
├── requirements.txt          ← Liste des dépendances Python
│
├── app-android/              ← Application Android complète
│   └── app/src/main/java/
│       └── com/blind_helmet/app/
│           ├── MainActivity.java        ← Chef d'orchestre de l'app
│           ├── WebSocketManager.java    ← Gestion de la connexion réseau
│           ├── AudioFeedback.java       ← Synthèse vocale + vibrations
│           ├── DetectionData.java       ← Structures de données
│           ├── AnnotatedImageView.java  ← Affichage avec boîtes colorées
│           ├── SpeechController.java    ← Reconnaissance vocale
│           └── YOLOHelper.java          ← Inférence locale (non activé)
│
└── firmware/
    └── esp32_cam/            ← Code pour la caméra embarquée (futur)
```

''''''''''''i''''''''''

### Module 1 : `vision_server.py` — Le serveur Python

**Rôle :** C'est le moteur principal. Il capture les images, les analyse, et envoie les résultats à l'application Android.

**Définitions des technologies utilisées :**

> **Python** : WHY ? Syntaxe simple et BEAUCOUP bibliothèques (comme OpenCV et YOLOv8) donc TB pour le traitement d'images et l'intelligence artificielle.

> **OpenCV** (*Open Computer Vision*) : Bibliothèque Python spécialisée dans le traitement d'images et la vision par ordinateur. Elle permet d'accéder à la webcam, de lire les images pixel par pixel et de les convertir dans différents espaces de couleur (RGB, HSV...). Ici elle capture les images à 8 images par seconde. cf shémas flux de données dessus (A.3)

> **YOLOv8** (*You Only Look Once, version 8*) : Modèle d'IA  de type réseau de neurones convolutifs (ie ca apprend pendant l'entrainement genre il a des couches empilées (ca vient des maths l'éthimologie)), spécialisé dans la détection d'objets en temps réel. "You Only Look Once" signifie qu'il analyse l'image en une seule passe, très fast du coup ! (~40ms sur GPU). Il est pré-entraîné sur 80 classes d'objets (personnes, voitures, animaux, feux, etc.) via le jeu de données COCO (qu'on etoffera au fur et a mesure du temps en "additionnant" les datasets (jeux de donnés) pour avoir par exemple des feux de passage pietons verts detectés et annoncés). Le fichier `yolov8n.pt` est le fichier de poids du modèle (`n` = nano, la version la plus légère), on verra a lavenir pour le modifier pour ameliorer les performances. Le fichier de poids, c'est un petit peu la structure de ton modele cad que il contient les millions de parametres (cest des nombres) que le reseau a appris pendant lentrainement (le modele ne sait rien faire sans).

DEMO :
En effet, un reseau de neurone est une chaine (ENORME) de multiplications et d'additions (des milliards de calculs pour une image 640x480) de la forme :

Pixel[0] × Poids[0] + Pixel[1] × Poids[1] + ... + Pixel[307200] × Poids[307200] = Résultat

*Sans les poids : calculs sur du vide
*Avec les poids ajustés pendant l'entrainement (ici 80h sur GPU) :
    Image d'une personne → calculs avec les bons poids → score "personne" = 0.85
    Image d'une voiture  → calculs avec les bons poids → score "voiture"  = 0.92
    Image d'un arbre     → calculs avec les bons poids → scores tous <0.3  → "rien détecté"


> **WebSocket** : Protocole de communication réseau qui maintient une connexion ouverte en permanence entre deux machines (contrairement à HTTP qui ouvre/ferme une connexion à chaque échange, ce qui faisait beaucoup trop de latence). Idéal pour envoyer des données en continu, comme des images analysées toutes les 125ms mais on pourrait faire mieux !

DEMO (pk mieux que http)

*HTTP: Android :

 "Donne-moi une image" → 
PC      : "Voilà l'image" →
[connexion fermée]

Android : "Donne-moi une autre image" →
PC      : "Voilà" →
[connexion fermée]

.
.
.
Il faut attendre a chaque fois le retour de l'image precedente et faux retablir une nouvelle connexion a chaque requete!!!

*Websocket: canal ouvert


Android : "Je me connecte"
PC      : "OK, connexion ouverte"
[canal reste ouvert]

PC → Android : image 1
PC → Android : image 2
PC → Android : image 3
... 8 fois par seconde, pendant juqua interruption de la communication (deconnexion)

Sur l'app : (cf. 15)
Android → PC : commande d'arret



> **JSON** (*JavaScript Object Notation*) : Format texte standardisé pour structurer et transmettre des données. Lisible par un humain, il permet d'envoyer les détections sous une forme compréhensible par n'importe quel système (Android, Python, etc.).

Exemple du format json:

 {"type": "frame",
  "detections": [
    {"class": "person", "confidence": 0.87, "position": "gauche"},
    {"class": "car", "confidence": 0.92, "position": "droite"}
  ], }



> **asyncio** : Module Python permettant d'exécuter plusieurs tâches en parallèle sans bloquer le programme (ex: recevoir des connexions WebSocket PENDANT que l'analyse IA tourne).

DEMO

*Sans asyncio
while True:
    frame = cap.read()           # Attend 33ms
    results = model(frame)       # Attend 42ms
    send_to_client(results)      # Attend 5ms
    # Total = 80ms → bloque tout le reste
Si y'a un probleme coté client tu l'as compris, ca bloque le reste.

Disons que c'est pas nécessairement utile : pour la pres du projet, par contre, c'est pertinent, car plusieurs personnes peuvent se connecter sur le serveur en meme temps sans qu'il y ait de blocage

*avec asyncio, c'est inutile de preciser, les clients tournent en parallele


> **NumPy** : Bibliothèque Python de calcul numérique. Permet de manipuler les images comme des tableaux de nombres (clairement c des matrices), indispensable pour les calculs d'IA. (cf. 16)

**Ce que fait le fichier, fonction par fonction :**

| Fonction | Rôle | cf. |
|---|---|---|
| `extract_detections()` | Transforme la sortie brute de YOLOv8 en liste JSON lisible | Pas pertinent (simple parsing de structure YOLOv8, déjà expliqué) |
| `detect_person_groups()` | Détecte si ≥ 3 personnes sont proches (rayon 150px) | cf. 11 |
| `detect_fast_movement()` | Compare positions entre frames pour détecter un objet en mouvement rapide (>200px/s) | cf. 12 |
| `detect_traffic_light_color()` | Analyse les pixels HSV du feu pour déterminer sa couleur + méthode de secours par position (ON VA LA SUPPRIMER BIENTOT car ne fonctionne pas en cas réel : il faut ajouter la détection de feu piétons verts spécifiquement) | cf. 13 |
| `analyze_weather_conditions()` | Analyse la luminosité et le contraste global de l'image dans le but de retourner l'alerte de danger si nécessaire | cf. 1d |
| `analyze_advanced_alerts()` | Orchestre toutes les analyses ci-dessus | Pas pertinent (appelle juste les fonctions ci-dessus, pas de logique propre) |
| `stream_frames()` | Boucle principale : capture → analyse → envoi JSON (cf. 17) | cf. 17 |
| `broadcast()` | Envoie le JSON à tous les clients connectés | cf. 3, 15 |
| `handle_client()` | Gère l'arrivée/départ d'un client WebSocket | cf. 15 |

---

### Module 2 : `MainActivity.java` — Chef d'orchestre Android


''''''''''i'''''''''
'''''''''mi'''''''''

**Rôle :** Point d'entrée de l'application Android. Coordonne tous les autres modules, reçoit les données du serveur, et décide quoi dire et quand.

> **Java** : Langage de programmation orienté objet, langage natif d'Android. Chaque comportement de l'application (boutons, affichage, audio) est codé en Java.

> **Android** : Système d'exploitation mobile de Google. Une application Android est composée d'`Activity` (écrans) et de composants. `MainActivity` est l'écran principal.

> **Handler / Looper** (cf. 19) : Mécanisme Android pour exécuter du code sur le thread UI (interface graphique) depuis un autre thread. Essentiel car les règles Android interdisent de modifier l'interface depuis un thread de fond.

> **ExecutorService** : Gère un pool de threads Java pour exécuter des tâches en arrière-plan sans bloquer l'interface. Non utilisé dans ce projet car `runOnUiThread()` et les callbacks asynchrones d'OkHttp suffisent.


'''''''''''''''''''''



**Responsabilités clés :**
- Initialise tous les composants au démarrage
- Reçoit chaque frame analysée via `WebSocketManager`
- Applique les règles anti-spam (cooldown 3s par classe, cooldown 5s pour alertes avancées)
- Décide si une alerte doit être annoncée vocalement
- Met à jour les statistiques affichées (FPS, latence, objets détectés)

**Orchestration complète :** (cf. 18)

---



### Module 3 : `WebSocketManager.java` — Connexion réseau (cf. 4, 15)


****Ici (module 3), passer plus rapidement en revue (c'est moins important!, faut juste comprendre rapidement comment ca marche !)****


**Rôle :** Gère la connexion WebSocket entre l'application Android et le serveur Python. Reçoit les messages JSON et les transforme en objets Java utilisables.

> **OkHttp** (cf. 20) : Bibliothèque Java/Android spécialisée dans les communications réseau. Elle gère les connexions WebSocket de manière fiable, avec reconnexion automatique en cas de coupure.

> **Parsing JSON** (cf. 21) : Opération de lecture et conversion d'un texte JSON en objet Java structuré. Ex: `{"class": "person"}` → objet `Detection` avec un champ `class = "person"`.

Ce module reçoit le JSON brut du serveur et remplit les objets `DetectionData` qui seront ensuite utilisés par `MainActivity`.

---

### Module 4 : `DetectionData.java` — Structures de données

**Rôle :** Définit les "moules" (classes Java) qui représentent les données reçues du serveur.

> **Classe Java (modèle de données)** : Structure qui définit les champs d'un objet. Ex: `Detection` a les champs `class`, `confidence`, `position`. C'est l'équivalent d'un formulaire vide qu'on remplit avec les données recues.

Classes définies :
- `Detection` : un objet détecté (classe, confiance, position, boîte)
- `PersonGroup` : groupe de personnes (nombre, position)
- `FastMovement` : mouvement rapide (type, direction, vitesse)
- `TrafficLight` : feu de circulation (couleur, côté) a supprimer (meme raison : ON VA LA SUPPRIMER BIENTOT car ne fonctionne pas en cas réel : il faut ajouter la détection de feu piétons verts spécifiquement)
- `Obstacle` : objet proche (classe, côté, taille)
- `AdvancedAlerts` : conteneur regroupant toutes les alertes avancées

---

### Module 5 : `AudioFeedback.java` — Synthèse vocale (cf. 6, 10)

### Ici (module 5), MOINS IMPORTANT (passer rapidement le module 5, comprendre comment ca marche)



**Rôle :** Transforme les détections en retours sensoriels pour l'utilisateur.

> **TTS (Text-To-Speech)** : Technologie de synthèse vocale. Android intègre nativement un moteur TTS qui convertit du texte en parole. Ici configuré en français.

> **Vibrator** : API Android pour contrôler le moteur de vibration du téléphone. Permet de créer des patterns de vibration (courte = info, longue répétée = danger).

Ce module fait la traduction des noms de classes anglaises (noms COCO) en français (trolling) :
- `person` → "Personne"
- `car` → "Voiture"
- `traffic light` → "Feu"
- `horse` → "Cheval", etc.

---

### Module 6 : `AnnotatedImageView.java` — Affichage avec boîtes (cf. 5)

**Rôle :** Vue Android personnalisée qui affiche l'image reçue ET dessine par-dessus les boîtes de détection colorées.

> **View personnalisée** : En Android, on peut créer ses propres composants visuels en héritant de la classe `View` et en redéfinissant la méthode `onDraw()`. En gros, on dessine des rectangles et du texte par-dessus l'image caméra (on superpose hehe!)

Couleurs utilisées :
- 🔴 Rouge : objet dangereux (confiance > 0.8)
- 🟠 Orange : objet à surveiller (confiance > 0.5)
- 🟢 Vert : information (confiance > 0.25)

Ces intervalles peuvent etre modifié 
---

### Module 7 : `SpeechController.java` — Reconnaissance vocale

**Rôle :** Permet à l'utilisateur de donner des commandes vocales après une alerte.

> **SpeechRecognizer** : API Android de reconnaissance vocale. Écoute le microphone et convertit la parole en texte. Requiert la permission `RECORD_AUDIO`.

Commandes reconnues :
- "OK" / "arrête" → ignore l'alerte en cours
- "répète" → répète la dernière alerte
- "désactiver" → désactive les alertes météo pendant 5 minutes

### NOTE IMPORTANT ! (probleme déja soulevé) La reconnaissance vocale est actuellement désactivée dans le code (commentée) car elle générait trop de faux positifs. Elle reste disponible pour une activation future.

---

### Module 8 : `YOLOHelper.java` — Inférence locale (non activé) (cf. 7)
### MODULE DU PROJET FINI (modele sur le telephone sans serveur python)
**Rôle :** Permettrait de faire tourner YOLOv8 directement sur le téléphone, sans serveur Python.

> **TFLite (TensorFlow Lite)** : Version allégée de TensorFlow (framework d'IA de Google) c'est une librairie pour faire du machine learning etc et c'est optimisée pour les appareils mobiles. Permet de faire tourner des modèles d'IA sur téléphone avec une consommation réduite (A VOIR si on change evaluation de perf needed)

> **Quantization INT8** : Technique de compression d'un modèle IA qui réduit la précision des calculs (de 32 bits à 8 bits). Résultat : modèle 4x plus petit, 2-3x plus rapide, avec une légère perte de précision (~2%). A voir si on peut encore faire mieux

Ce module est **désactivé** (`yoloHelper = null` dans `MainActivity`). Il sera activé pour le projet final sans PC.

---









## 4. Journal de conception chronologique
### PROCESSUS (AI GENERATED)
### Étape 1 — Comprendre le problème et définir l'architecture

**Ce que j'ai fait :**
J'ai d'abord réfléchi à ce que le système devait faire : aider une personne malvoyante à identifier les objets autour d'elle en temps réel. Le premier travail a été de définir l'architecture globale avant d'écrire une seule ligne de code.

**Questions posées :**
- Où faire tourner l'IA ? Sur le téléphone, sur un serveur local, ou dans le cloud ?
- Comment faire communiquer la caméra avec l'application ?
- Quel format de communication entre le serveur et l'app ?

**Options envisagées :**

| Option | Avantage | Inconvénient |
|---|---|---|
| IA sur le téléphone | Autonome | Lent (~500ms), batterie |
| IA sur serveur cloud | Puissant | Coût, latence réseau, internet requis |
| IA sur PC local | Rapide, gratuit | PC requis |

**Solution retenue :** IA sur PC local pour la démonstration, avec une architecture pensée pour migrer vers le téléphone plus tard. Le PC joue le rôle du serveur pendant les tests.

**Sous-étapes techniques :**
1. Définition du protocole de communication (WebSocket choisi pour sa persistance)
2. Définition du format des échanges (JSON choisi pour sa lisibilité)
3. Séparation claire serveur Python / client Android
4. Préparation de la structure de dossiers du projet


---

### Étape 2 — Mise en place du serveur Python et de la détection YOLOv8

**Ce que j'ai fait :**
Installation des dépendances Python (`ultralytics`, `opencv-python`, `websockets`), chargement du modèle `yolov8n.pt` et premier test de détection sur une image fixe.

**Questions posées :**
- Quel modèle YOLO choisir ? (nano, small, medium, large)
- Quel seuil de confiance fixer pour éviter les faux positifs ?
- Comment structurer la sortie de YOLOv8 en JSON lisible ?

**Options envisagées :**

| Modèle | Taille | Vitesse | Précision |
|---|---|---|---|
| YOLOv8n (nano) | 6 MB | ~40ms | Correcte ✅ |
| YOLOv8s (small) | 22 MB | ~80ms | Bonne |
| YOLOv8m (medium) | 52 MB | ~150ms | Très bonne |

**Solution retenue :** `yolov8n` — le plus rapide, suffisamment précis pour des objets courants à distance raisonnable.

**Sous-étapes techniques :**
1. On crée la fonction `extract_detections()` qui transforme la sortie brute de YOLOv8 (tenseurs de coordonnées) en une liste JSON structurée avec classe, confiance, et coordonnées de la boîte.
2. On met en place le calcul de la position horizontale (gauche/centre/droite) en divisant la largeur de l'image en 3 zones.
3. On filtre les détections sous le seuil de confiance de 0.45 pour éliminer les faux positifs.
4. On mesure le temps d'inférence avec `time.time()` pour l'afficher dans les statistiques Android.
5. On prépare la structure JSON finale incluant la liste de détections, le temps d'inférence et un identifiant de frame.

**Note :** À ce stade, le serveur tourne seul, sans aucune connexion avec la partie capteurs 

---

### Étape 3 — Mise en place du WebSocket et de la boucle de streaming

**Ce que j'ai fait :**
Création de la boucle principale `stream_frames()` qui capture en continu, analyse et diffuse. Mise en place du serveur WebSocket avec `websockets` et `asyncio`.

**Questions posées :**
- Comment gérer plusieurs clients connectés simultanément ?
- Comment limiter le débit pour ne pas saturer le réseau ?
- Comment encoder l'image pour l'envoyer dans un JSON texte ?

**Solution retenue :**
- 8 images par seconde (suffisant pour la détection, pas trop gourmand)
- Encodage JPEG à 75% de qualité pour réduire la taille
- Encodage Base64 pour intégrer l'image dans le JSON texte
- `asyncio.gather()` pour diffuser en parallèle à tous les clients

> **Base64** : Système d'encodage qui convertit des données binaires (comme une image) en texte ASCII. Permet d'intégrer une image dans un JSON, qui est un format texte. L'image grossit d'environ 33% mais devient transportable dans n'importe quel message texte.

**Sous-étapes techniques :**
1. On crée la fonction `broadcast()` qui envoie le JSON à tous les clients connectés en parallèle via `asyncio.gather()`.
2. On implémente la gestion dynamique de la liste de clients connectés (ajout à la connexion, suppression à la déconnexion).
3. On encode chaque frame JPEG en Base64 pour l'intégrer dans le JSON.
4. On met en place un contrôle du débit avec `asyncio.sleep()` pour maintenir 8 FPS.
5. On ajoute un compteur de frames pour permettre le suivi de la cohérence côté Android.


### Étape 4 — Création de l'application Android de base

**Ce que j'ai fait :**
Création du projet Android Studio, configuration du `build.gradle`, mise en place du layout principal, et première connexion WebSocket réussie.

**Questions posées :**
- Quelle bibliothèque utiliser pour le WebSocket Android ?
- Comment afficher l'image reçue en temps réel sans bloquer l'interface ?
- Comment decoder le Base64 en image affichable ?

> **Android Studio** : Environnement de développement (IDE) officiel pour créer des applications Android. Intègre un émulateur Android qui simule un téléphone sur le PC.

> **build.gradle** : Fichier de configuration du projet Android qui déclare les dépendances (bibliothèques tierces), la version minimale d'Android supportée, et les options de compilation.

> **OkHttp** : Bibliothèque réseau pour Android/Java. Gère les connexions WebSocket de manière robuste. Choisie pour sa fiabilité et sa documentation complète.

> **Thread UI vs Thread de fond** : Android impose que toute modification de l'interface graphique se fasse sur le "thread principal" (UI thread). Le traitement réseau doit se faire sur un thread de fond pour ne pas bloquer l'écran. Le `Handler` sert de pont entre les deux.

**Sous-étapes techniques :**
1. On crée `WebSocketManager.java` responsable de maintenir la connexion, de reconnecter automatiquement, et d'informer `MainActivity` via un callback à chaque message reçu.
2. On implémente le décodage Base64 → tableau de bytes → `Bitmap` Android dans le thread de fond.
3. On met en place le mécanisme `isUIUpdatePending` pour éviter l'accumulation de frames en attente si le téléphone est plus lent que le serveur.
4. On utilise `runOnUiThread()` pour basculer vers le thread UI uniquement au moment de l'affichage.
5. On prépare la structure de callback `WebSocketCallback` avec les événements `onConnected`, `onDisconnected`, `onFrameReceived`.

---

### Étape 5 — Parsing JSON et structure des données

**Ce que j'ai fait :**
Création de `DetectionData.java` et complétion de `WebSocketManager` pour transformer le JSON reçu en objets Java utilisables par `MainActivity`.

**Questions posées :**
- Comment représenter une détection en Java de manière propre ?
- Comment gérer les champs absents dans le JSON sans crash ?

> **`optString()`, `optInt()`, `optDouble()`** : Méthodes de la bibliothèque `org.json` qui lisent un champ JSON et retournent une valeur par défaut si le champ est absent. Évitent les crashes dus à des champs manquants.

**Sous-étapes techniques :**
1. On crée les classes de données Java (`Detection`, `TrafficLight`, `PersonGroup`, `Obstacle`, `FastMovement`) avec leurs champs respectifs.
2. On implémente le parsing du tableau `detections` du JSON vers une `List<Detection>` Java.
3. On ajoute le parsing du bloc `advanced_alerts` vers l'objet `AdvancedAlerts`.
4. On prépare la gestion des valeurs manquantes avec des valeurs par défaut pour éviter les `NullPointerException`.
5. On transmet le `DetectionData` complet via le callback `onFrameReceived` vers `MainActivity`.

---

### Étape 6 — Synthèse vocale et logique d'alertes

**Ce que j'ai fait :**
Création d'`AudioFeedback.java`, configuration du TTS en français, et implémentation de la logique anti-spam dans `MainActivity`.

**Questions posées :**
- Comment éviter de répéter "Personne à gauche" 8 fois par seconde ?
- Comment distinguer un objet qui a bougé d'un objet qui est resté au même endroit ?
- Comment prioriser les alertes (voiture > personne > chaise) ?

**Solution retenue :** Système de double anti-spam :
1. **Cooldown temporel** : 3 secondes minimum entre deux annonces du même objet
2. **Détection de mouvement** : si l'objet a bougé de plus de 50 pixels depuis la dernière annonce, on réannonce même si le cooldown n'est pas écoulé

**Sous-étapes techniques :**
1. On initialise le moteur TTS Android avec la locale française et un pitch légèrement ralenti pour une meilleure compréhension.
2. On crée la `Map<String, Long> lastAnnouncedTime` pour enregistrer le timestamp de la dernière annonce par classe d'objet.
3. On crée la `Map<String, Detection> lastDetectionPosition` pour comparer les positions entre frames.
4. On implémente la fonction `shouldAnnounce()` qui combine les deux critères (temps ET mouvement).
5. On crée la table de traduction anglais → français pour les 80 classes COCO.
6. On implémente les patterns de vibration différenciés (danger = 3 longues, warning = 2 courtes, info = 1 courte).

---

### Étape 7 — Alertes avancées (groupes, mouvements, feux, obstacles)

**Ce que j'ai fait :**
Ajout de 4 nouvelles fonctions d'analyse dans `vision_server.py` pour détecter des situations plus complexes que les simples objets isolés.

**Questions posées :**
- Comment détecter un groupe de personnes et pas juste "3 personnes" séparément ?
- Comment détecter qu'une voiture est en mouvement rapide ?
- Comment identifier la couleur d'un feu piéton (et pas d'un feu voiture) ?
- Comment filtrer les obstacles qui sont vraiment proches versus loin ?

**Sous-étapes techniques :**

**Groupes de personnes :**
1. On filtre les détections pour ne garder que les `person`.
2. On applique un algorithme de clustering simple : pour chaque personne, on cherche les autres à moins de 150 pixels.
3. Si un cluster contient ≥ 3 personnes, on l'ajoute à la liste des groupes avec sa position moyenne.

**Mouvement rapide :**
1. On stocke les positions de chaque objet à la frame précédente dans `previous_detections`.
2. On calcule la distance euclidienne (√(Δx² + Δy²)) entre position actuelle et position précédente.
3. On divise par le delta de temps (0.125s = 1/8 FPS) pour obtenir une vitesse en pixels/s.
4. Si la vitesse dépasse 200px/s, on génère une alerte.

**Feux piétons :**
1. On filtre les détections `traffic light` et on calcule le ratio hauteur/largeur du bounding box.
2. Si ratio > 2.2 → feu voiture (3 disques empilés, très allongé) → ignoré.
3. Si ratio ≤ 2.2 → feu piéton (plus carré) → on analyse la couleur.
4. On convertit le ROI (région d'intérêt) en espace colorimétrique HSV.
5. On compte les pixels rouges (Hue 0-15° et 160-180°) avec seuil de saturation et luminosité.
6. Méthode de secours : on divise le feu en 3 zones (haut/milieu/bas) et on identifie la zone la plus lumineuse.

> **HSV (Hue, Saturation, Value)** : Espace colorimétrique alternatif au RGB. "Hue" = teinte (0-180 dans OpenCV : 0=rouge, 60=jaune, 120=vert), "Saturation" = intensité de la couleur, "Value" = luminosité. Beaucoup plus pratique que RGB pour identifier des couleurs spécifiques car la teinte est indépendante de l'éclairage.

**Obstacles proches :**
1. Pour chaque objet détecté dans les classes à risque (personne, voiture, vélo, chien...).
2. Si le bas du bounding box (y2) dépasse 66% de la hauteur de l'image → l'objet est dans le tiers inférieur → il est proche.
3. On calcule la taille relative pour qualifier "grand" ou normal.

---

### Étape 8 — Interface dark mode et statistiques

**Ce que j'ai fait :**
Refonte visuelle complète de l'application en mode sombre, et ajout des statistiques en temps réel (FPS, latence, objets détectés, temps d'inférence).

**Choix de design :**
- Fond `#121212` (noir profond standard Material Design dark)
- Surfaces `#1E1E1E` (gris très foncé pour la lisibilité)
- Texte `#E0E0E0` (blanc cassé, moins agressif que blanc pur)
- Accent `#64B5F6` (bleu Material Design)

**Sous-étapes techniques :**
1. On modifie `colors.xml` pour définir la palette dark mode.
2. On met à jour `themes.xml` avec le thème `MaterialComponents.DayNight`.
3. On modifie `rounded_background.xml` pour le drawable de fond des blocs.
4. On ajoute la mesure `inference_time_ms` dans `vision_server.py` avec `time.time()` avant/après l'appel YOLOv8.
5. On envoie `inference_time_ms` dans le JSON et on le parse dans `WebSocketManager`.
6. On met à jour les statistiques à chaque frame (FPS calculé sur fenêtre glissante, latence = timestamp envoi - timestamp réception).

---

## 5. Ce qui manque encore

### ❌ Connexion avec la partie capteurs (non faite)

**Situation actuelle :**
Les deux parties du projet fonctionnent en parallèle mais ne communiquent pas. Il n'existe aucun protocole d'échange entre la partie IA (logicielle) et la partie capteurs/haptique.

**Ce que ça veut dire concrètement :**
- La partie IA ne sait pas ce que les ultrasons ont détecté
- La partie capteurs ne sait pas ce que YOLOv8 a identifié
- Les vibrations du casque et les annonces vocales sont indépendantes

---

### Ce qu'il faudrait ajouter pour intégrer les deux parties

**Option 1 (la plus simple) : L'ESP32 envoie ses données au téléphone**

```
ESP32 (ultrasons) → WiFi → Téléphone Android
```

L'ESP32 enverrait un message simple au téléphone :
```json
{"ultrasonic_left": 45, "ultrasonic_right": 120}
```
Le téléphone pourrait croiser cette information avec YOLOv8 pour affiner les alertes.

**Option 2 : Le téléphone envoie des commandes à l'ESP32**

```
Téléphone Android → WiFi → ESP32 (vibreurs/buzzer)
```

Le téléphone enverrait des commandes selon les détections IA :
```json
{"vibrate": "danger", "side": "left"}
{"buzzer": "warning"}
```

**Ce qu'il faudrait définir ensemble :**
1. Le protocole de communication (WebSocket ? UDP ? MQTT ?)
2. Le format des messages échangés
3. Qui initie la connexion (l'ESP32 ou le téléphone ?)
4. La gestion des conflits (ex : les deux systèmes veulent vibrer en même temps)

---

### Ce qu'il faudrait ajouter pour le projet final (sans PC)

1. **Activer `YOLOHelper.java`** : le module est déjà écrit, juste désactivé
2. **Convertir le modèle** : `yolov8n.pt` → `yolov8n_int8.tflite` (script Python fourni)
3. **Remplacer la source vidéo** : Webcam PC → stream MJPEG de l'ESP32-CAM
4. **Supprimer `vision_server.py`** : toute la logique migre dans l'app Android

---

*Rapport rédigé en mars 2026 — Partie logicielle & IA du projet casque malvoyants*
