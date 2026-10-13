# LibreHU Apps (OTA)

Gestionnaire des applis LibreHU pour autoradios Jancar / Autochips AC8257 (UJC201) : liste les applis, compare la
version installée à la dernière publiée sur GitHub, installe et met à jour. Thème clair / sombre et couleur d'accent
de [LibreHU Launcher](https://github.com/LibreHU/LibreHU-Launcher-App).

## Interface

Organisée comme [Aurora Store](https://github.com/whyorean/AuroraStore) (GPL-3.0, client libre du Play Store), adaptée à
l'écran paysage de l'autoradio : une **barre de navigation à gauche** et une barre de titre (état de la dernière
vérification, bouton Vérifier).

| Page | Contenu |
|---|---|
| **Applis** | tuiles en deux sections, *Installées* puis *À installer* : icône, version, pastille d'état (À jour, Mise à jour, Non installée, Téléchargement…), action principale (Installer / Mettre à jour / Ouvrir, ou anneau de progression avec pause et annulation) ; toucher une tuile ouvre sa fiche |
| **Mises à jour** (badge : leur nombre) | nombre et **Tout mettre à jour**, une ligne par appli (versions avant → après, taille, date, nouveautés dépliables avec les commits), les mises à jour ignorées à part |
| **Fiche d'une appli** | en-tête (icône, paquet, dépôt, état), actions (Ouvrir, Réinstaller, Journal, Installer dans /system, Ne pas mettre à jour), avancement, versions dans /data et /system, version GitHub, branche, date, taille, nouveautés et commits, **toutes les versions** (chargées à la demande : une requête GitHub) à installer, revenir à une plus ancienne ou mettre dans /system |
| **Zips** | deux onglets : *Zips flashables* et *Fichiers de configuration* |
| **Téléchargements** (badge : ceux en cours) | en cours ou en pause (avancement, débit, temps restant, pause / reprise / annulation), terminés ou en échec, puis l'**historique** |
| **Réglages** | sections Général (édition, root), Vérifications, Installation, /system, Sources (jeton GitHub, index des zips), Stockage, À propos |

Les choix qu'une installation attend (autre signature, version plus ancienne, emplacement dans /system) s'ouvrent
quelle que soit la page affichée. Le code de l'interface est rangé par page (`ui/apps`, `ui/updates`, `ui/details`,
`ui/downloads`, `ui/zips`, `ui/settings`, composants communs dans `ui/components`) ; les couches données (`data/`) et
installation (`install/`) n'ont pas changé.

## Fonctions

- **Catalogue** : [catalog.json](catalog.json) de ce dépôt (téléchargé à chaque vérification), avec une copie
  intégrée à l'appli quand GitHub n'est pas joignable. Pour ajouter une appli : une entrée dans ce fichier. Il
  comprend les forks LibreHU d'Open Headunit (« Android Auto », `LibreHU/LibreHU-open-headunit-app`) et de
  xcertplay (« CarPlay », `LibreHU/LibreHU-xcertplay-app`, branche `master`).
- **Releases** : chaque dépôt publie, à chaque push, une pré-release continue par branche (`latest-<branche>`) avec
  l'APK et sa version dans les notes (`versionCode=…`, `versionName=…`), par l'étape « Rolling release » de son
  workflow `build.yml`. Il garde aussi les **15 dernières compilations** de chaque branche, en releases numérotées
  `build-<branche>-<versionCode>`.
- **Édition** : `LibreHU service` (branches `librehu-service`), `Jancar ivi-services` (branches `ivi`) ou `Android
  générique` (`main`) ; une appli publiée sur une seule branche ignore ce choix.
- **Installation** : en root (Magisk, KernelSU, KernelSU Next, APatch…) `pm install` silencieux ; sans root,
  l'installeur d'Android (autoriser « installer des applis inconnues » pour LibreHU Apps).
- **Signature** : toutes les applis LibreHU sont signées avec la même clé (`keystore/librehu.jks`), une mise à jour
  s'installe donc par-dessus la précédente en gardant ses données. Les APK des anciens builds avaient chacun une clé
  aléatoire : la première installation par LibreHU Apps propose de **désinstaller puis installer** (réglages perdus),
  ou, pour une appli installée dans `/system` (privilégiée), de **la remplacer dans /system** en root (données
  gardées, redémarrage nécessaire).
- **Versions** (fiche de l'appli) : les compilations gardées de l'édition, avec date et dernier commit ;
  en choisir une affiche les commits ajoutés depuis la version installée (ou retirés en revenant en arrière, via
  l'API « compare » de GitHub), puis l'installe. **Revenir à une version plus ancienne** : Android refuse
  d'installer un `versionCode` plus petit par-dessus (sauf appli ou ROM debuggable) ; LibreHU Apps propose alors de
  désinstaller puis installer (réglages perdus) ou, en root, de garder les données (`pm uninstall -k`, risqué si la
  version récente a changé le format de ses réglages).
- **Journal** (fiche d'une appli installée) : les dernières lignes du logcat de l'appli (ses processus, par
  identifiant Android, et ses plantages), actualisables et enregistrables dans un fichier. Il faut le root, ou
  accorder une fois la permission depuis un ordinateur puis rouvrir l'appli :

  ```
  adb shell pm grant org.librehu.ota android.permission.READ_LOGS
  ```
- **Installer dans /system** (root) : l'APK devient une appli système, dans `/system/priv-app/<paquet>` ou
  `/system/app/<paquet>`. LibreHU Apps présélectionne **priv-app** quand l'APK demande des permissions privilégiées
  (seules accordées depuis priv-app) ou qu'il y est déjà, `/system/app` sinon. En priv-app, ces permissions sont
  ajoutées à la liste blanche `privapp-permissions-<paquet>.xml` de `/system/etc/permissions` : sans elle, Android 9
  refuse de démarrer quand `ro.control_privapp_permissions=enforce`. Actif après un redémarrage ; la version
  installée dans /data reste (mise à jour de l'appli système, données gardées si même clé). Deux façons :
  - **Module root** (recommandé, Magisk, KernelSU, KernelSU Next, APatch) : un module `librehu_<paquet>` installé par
    la commande du gestionnaire (`magisk --install-module`, `ksud module install`, `apd module install`), monté
    par-dessus /system à chaque démarrage. La partition n'est pas modifiée : fonctionne avec dm-verity ou un /system
    en lecture seule, et se retire depuis la liste des modules. Une ancienne copie ailleurs dans /system est masquée
    (`.replace`). KernelSU officiel 3.0 et plus ne monte les fichiers /system des modules qu'avec un **metamodule**
    (meta-overlayfs) ; KernelSU Next et Magisk les montent eux-mêmes.
  - **Par TWRP** (recommandé sur l'UJC201 sans gestionnaire root à modules) : en démarrage normal, le noyau stock
    protège des zones de l'eMMC en écriture (pilote MSDC propriétaire : `write protect region`, `hw write protect
    violation`) ; l'écriture directe dans /system finit donc en **erreur d'E/S** même une fois la partition montée en
    écriture. LibreHU Apps prépare alors un petit zip (APK, liste blanche des permissions, ancienne copie retirée),
    l'inscrit dans OpenRecoveryScript et redémarre en recovery : TWRP l'écrit, vérifie l'APK et relance Android.
  - **Écrire dans /system** : `mount -o rw,remount` ne marche pas sur l'autoradio (system-as-root : / apparaît
    comme `/dev/root`, un nœud qui n'existe pas, donc la protection en lecture seule qu'Android met sur le bloc ne
    peut pas être levée par lui ; et avec Magisk, `/system/priv-app` peut être un tmpfs où l'écriture serait perdue).
    Le vrai bloc (`by-name/system`) est donc monté une seconde fois dans un dossier privé, ce montage passé en
    écriture, les fichiers écrits à travers lui, puis tout est remis en lecture seule ; le montage d'Android n'est
    pas touché. Refusé si la partition est sous dm-verity (bloc esclave d'un `dm-*`), car écrire sous dm-verity
    empêche de démarrer : l'appli l'indique avec la commande à lancer soi-même
    (`fastboot --disable-verity flash vbmeta vbmeta.img`) et propose le module. Pour retirer : supprimer son dossier et son fichier
    `privapp-permissions`.
- **Téléchargements** : débit (lissé sur ~2 s), quantité reçue / taille, pourcentage et **temps restant** sur la tuile, la ligne de mise à jour, la fiche et la page Téléchargements ; **pause / reprise** (le morceau reçu est gardé, reprise par requête HTTP `Range` avec
  `If-Range` sur l'ETag : un fichier remplacé sur GitHub repart de zéro ; taille vérifiée à la fin) et annulation.
- **Notifications** (Réglages, activées par défaut) : une par appli pendant le téléchargement (progression, débit,
  temps restant, boutons Pause / Annuler, puis Reprendre), l'installation, puis « Installé » ou l'échec ; aussi pour
  les installations de la vérification en arrière-plan. Tant que quelque chose tourne, un service au premier plan
  (résumé : nombre d'applis, progression totale) garde LibreHU Apps en vie jusqu'à la fin.
- **Versions installées** : chaque appli affiche sa version dans **/system** (la copie d'usine, même cachée par une
  mise à jour) et dans **/data**, ou « non installé », à côté de celle de GitHub. La copie /system cachée est lue par
  `PackageManager` (drapeau `MATCH_FACTORY_ONLY`, gardé seulement s'il désigne bien une partition système), sinon avec
  root par l'APK que nomme `dumpsys package` (une fois par mise à jour, sans bloquer l'écran).
- **Installations l'une après l'autre** : « Tout mettre à jour » télécharge en parallèle (2 par défaut) puis
  installe une appli à la fois (état « En attente »), LibreHU Apps elle-même en dernier (son processus se termine avec
  sa propre installation). Lancer plusieurs installations en même temps faisait échouer les suivantes ou fermer
  l'appli.
- **Ne pas mettre à jour** (fiche de l'appli ; liste dans Réglages › Installation, retirable aussi depuis Mises à jour) : l'appli est exclue de « Tout mettre à jour », des
  notifications et de l'installation en arrière-plan.
- **Réglages** : vérification en arrière-plan (activée, toutes les 6 h / 12 h / 1 j / 2 j / 7 j, Wi-Fi
  seulement, notification, **installation automatique** en root), méthode d'installation (automatique / root
  seulement / installateur Android), compilation après installation (speed-profile, une appli à la fois),
  téléchargements simultanés, garder ou supprimer les APK téléchargés, **jeton GitHub** facultatif (5000 requêtes
  par heure au lieu de 60, envoyé seulement à api.github.com), **historique** des installations, configurations et
  flashs (date, versions avant → après, résultat) dans la page Téléchargements.
- **Mise à jour automatique dans /system** (Réglages, root, désactivée par défaut) : les mises à jour (manuelles,
  « Tout mettre à jour », arrière-plan) vont dans `/system/priv-app` ou `/system/app` selon le besoin de l'appli,
  pour les applis déjà dans /system ou pour toutes ; par module root, par écriture dans /system, ou automatique
  (module si le gestionnaire root le monte, sinon écriture, et module en secours si /system reste en lecture seule).
  Option « aussi dans /data » (activée) : la nouvelle version tourne tout de suite, /system l'a au démarrage suivant.
  Si /system refuse, l'installation normale dans /data se fait à la place (noté dans l'historique). LibreHU Apps
  elle-même reste dans /data.
- **Zips › Fichiers de configuration** (root) : fichiers de configuration (politique audio, effets, config de la box CAN, config
  Jancar…) et **zips recovery** :
  - du catalogue (`configs` de [catalog.json](catalog.json)) :
    `{"id", "name", "description", "type": "FILE" | "RECOVERY_ZIP", "url", "dest", "mode", "owner", "sha256", "reboot"}` ;
  - ou un **fichier de l'appareil** placé à une destination au choix (raccourcis : `audio_policy_configuration.xml`,
    `audio_effects.xml`, `can_config.json`, `ivi-customer.ini`) ;
  - ou un **zip flashé par TWRP** : copié dans `/data/media/0/LibreHU/flash`, `install <zip>` écrit dans
    `/cache/recovery/openrecoveryscript`, puis redémarrage en recovery (TWRP l'exécute et redémarre).

  Le fichier remplacé est d'abord copié dans `/sdcard/LibreHU/backup`, son label SELinux gardé. Une partition en
  lecture seule (/system, /vendor, /jancar…) est écrite par le même montage privé que « Écrire dans /system »,
  **sauf si elle est protégée par dm-verity** : rien n'est écrit, car
  l'autoradio ne démarrerait plus ; désactiver d'abord verity (`fastboot --disable-verity flash vbmeta vbmeta.img`).
- **Zips › Zips flashables** (root) : les zips flashables de
  [LibreHU-flashablesZips](https://github.com/LibreHU/LibreHU-flashablesZips), lus dans `index.json` de sa release
  `zips` (copie gardée pour le hors-ligne), **classés par catégorie** (Recovery, Pilotes, Tweaks, Mises à jour,
  Noyau… : celles de `categories.json` du dépôt, filtres en haut de la liste). Pour chaque zip : version, taille,
  description, avertissement, licence et source ; incompatible si `ro.product.device` n'est pas dans ses `devices`.
  - **Flasher avec TWRP** : téléchargé, SHA-256 vérifié, puis même chemin que les fichiers de configuration (OpenRecoveryScript
    et redémarrage en recovery). Le zip refuse lui-même un autre appareil et s'arrête sans rien écrire si la
    partition qu'il modifie est sous dm-verity actif.
  - **Écrire dans `<partition>` maintenant** (zips accompagnés d'une image, TWRP par exemple) : sans passer par la
    recovery ; partition trouvée dans `by-name`, taille vérifiée, partition actuelle copiée dans
    `/sdcard/LibreHU/backup`, image écrite puis relue et comparée (SHA-256).
  - **Annuler** : flashe le zip de restauration associé (caché de la liste).
  - URL de l'index modifiable dans Réglages › Sources (dépôt de test ou fork).
- **Limite de l'API GitHub** (sans compte : 60 requêtes par heure et par adresse IP) : une vérification n'utilise
  plus l'API. Chaque appli publie à côté de son APK un `release.txt` (version, commit, nom et taille de l'APK), lu
  comme un simple téléchargement de release, qui ne compte pas dans ces 60 requêtes ; l'API ne sert plus qu'aux
  compilations publiées avant ce fichier, à la liste des **Versions** et aux **changements** d'une version. Ses
  réponses sont gardées avec leur ETag (une réponse inchangée revient en 304, non décomptée avec un jeton), les
  comparaisons de commits sont gardées, et une fois la limite atteinte plus rien n'est demandé à l'API avant
  l'heure de remise à zéro. Le catalogue (`raw.githubusercontent.com`) et l'index des Zips (release) ne passent pas
  par l'API non plus. Un jeton personnel (Réglages › Sources) porte la limite à 5000 requêtes par heure.

## Captures d'écran

| | |
|---|---|
| ![Applis](docs/screenshots/apps.png)<br>Applis | ![Fiche](docs/screenshots/details.png)<br>Fiche d'une appli |
| ![Versions](docs/screenshots/versions.png)<br>Versions | ![Journal](docs/screenshots/logs.png)<br>Journal |
| ![Mises à jour](docs/screenshots/updates.png)<br>Mises à jour | ![Zips](docs/screenshots/zips.png)<br>Zips |
| ![Téléchargements](docs/screenshots/downloads.png)<br>Téléchargements | ![Réglages](docs/screenshots/settings.png)<br>Réglages |

Captures sur l'émulateur Android 9 (1280×720, 160 dpi comme l'autoradio), sans autoradio branché : les valeurs du véhicule et de la MCU sont vides. Refaites-les avec l'action **Screenshots** de [LibreHU-OTA-app](https://github.com/LibreHU/LibreHU-OTA-app/tree/main/screenshots).

## Captures automatiques

L'action **Screenshots** ([screenshots/](screenshots/)) installe la dernière release de chaque appli sur un émulateur
Android 9 (1280×720, 160 dpi, en français ; Android 11 pour LibreHU service, FM et MCU Toolkit, dont le code natif
est ARM seulement), suit `screenshots/plan.json` (ouvrir, toucher un texte, capturer) et publie les PNG dans la
release `screenshots`. Ce sont les images des galeries des README.

## Construire

Action **Build** (artefact et release `latest-main`), ou `./gradlew :app:assembleDebug` avec le SDK Android.
