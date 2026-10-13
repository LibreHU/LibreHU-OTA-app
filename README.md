# LibreHU Apps (OTA)

Gestionnaire des applis LibreHU pour autoradios Jancar / Autochips AC8257 (UJC201) : liste les applis, compare la
version installée à la dernière publiée sur GitHub, installe et met à jour. Thème clair / sombre et couleur d'accent
de [LibreHU Launcher](https://github.com/LibreHU/LibreHU-Launcher-App).

- **Catalogue** : [catalog.json](catalog.json) de ce dépôt (téléchargé à chaque vérification), avec une copie
  intégrée à l'appli quand GitHub n'est pas joignable. Pour ajouter une appli : une entrée dans ce fichier.
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
- **Versions** (bouton sur chaque appli) : les compilations gardées de l'édition, avec date et dernier commit ;
  en choisir une affiche les commits ajoutés depuis la version installée (ou retirés en revenant en arrière, via
  l'API « compare » de GitHub), puis l'installe. **Revenir à une version plus ancienne** : Android refuse
  d'installer un `versionCode` plus petit par-dessus (sauf appli ou ROM debuggable) ; LibreHU Apps propose alors de
  désinstaller puis installer (réglages perdus) ou, en root, de garder les données (`pm uninstall -k`, risqué si la
  version récente a changé le format de ses réglages).
- **Journal** (bouton sur chaque appli installée) : les dernières lignes du logcat de l'appli (ses processus, par
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
  - **Écrire dans /system** : remonte /system (ou `/` en system-as-root) en écriture, en levant au besoin la
    protection en lecture seule du bloc ; laissé tel quel s'il s'agit d'un périphérique dm-verity (`/dev/block/dm-*`),
    car écrire sous dm-verity empêche de démarrer. Si /system reste en lecture seule, l'appli l'indique (avec ses
    points de montage) et propose le module. Pour retirer : supprimer son dossier et son fichier
    `privapp-permissions`.
- **Installations l'une après l'autre** : « Tout mettre à jour » télécharge en parallèle (2 par défaut) puis
  installe une appli à la fois (état « En attente »), LibreHU Apps elle-même en dernier (son processus se termine avec
  sa propre installation). Lancer plusieurs installations en même temps faisait échouer les suivantes ou fermer
  l'appli.
- **Ne pas mettre à jour** (bouton sur chaque appli) : l'appli est exclue de « Tout mettre à jour », des
  notifications et de l'installation en arrière-plan.
- **Onglet Paramètres** : vérification en arrière-plan (activée, toutes les 6 h / 12 h / 1 j / 2 j / 7 j, Wi-Fi
  seulement, notification, **installation automatique** en root), méthode d'installation (automatique / root
  seulement / installateur Android), compilation après installation (speed-profile, une appli à la fois),
  téléchargements simultanés, garder ou supprimer les APK téléchargés, **jeton GitHub** facultatif (5000 requêtes
  par heure au lieu de 60, envoyé seulement à api.github.com), **historique** des installations, configurations et
  flashs (date, versions avant → après, résultat).
- **Onglet Configs** (root) : fichiers de configuration (politique audio, effets, config de la box CAN, config
  Jancar…) et **zips recovery** :
  - du catalogue (`configs` de [catalog.json](catalog.json)) :
    `{"id", "name", "description", "type": "FILE" | "RECOVERY_ZIP", "url", "dest", "mode", "owner", "sha256", "reboot"}` ;
  - ou un **fichier de l'appareil** placé à une destination au choix (raccourcis : `audio_policy_configuration.xml`,
    `audio_effects.xml`, `can_config.json`, `ivi-customer.ini`) ;
  - ou un **zip flashé par TWRP** : copié dans `/data/media/0/LibreHU/flash`, `install <zip>` écrit dans
    `/cache/recovery/openrecoveryscript`, puis redémarrage en recovery (TWRP l'exécute et redémarre).

  Le fichier remplacé est d'abord copié dans `/sdcard/LibreHU/backup`. La partition est remontée en écriture le
  temps de l'opération, **sauf si elle est protégée par dm-verity** (`/dev/block/dm-*`) : rien n'est écrit, car
  l'autoradio ne démarrerait plus ; désactiver d'abord verity (`fastboot --disable-verity flash vbmeta vbmeta.img`).
- API GitHub sans compte : 60 requêtes par heure et par adresse IP ; une vérification en fait une par appli, la
  liste des versions une, les changements d'une version une ou deux.

## Captures d'écran

| | |
|---|---|
| ![Applis](docs/screenshots/apps.png)<br>Applis | ![Versions](docs/screenshots/versions.png)<br>Versions |
| ![Journal](docs/screenshots/logs.png)<br>Journal |  |

Captures sur l'émulateur Android 9 (1280×720, 160 dpi comme l'autoradio), sans autoradio branché : les valeurs du véhicule et de la MCU sont vides. Refaites-les avec l'action **Screenshots** de [LibreHU-OTA-app](https://github.com/LibreHU/LibreHU-OTA-app/tree/main/screenshots).

## Captures automatiques

L'action **Screenshots** ([screenshots/](screenshots/)) installe la dernière release de chaque appli sur un émulateur
Android 9 (1280×720, 160 dpi, en français ; Android 11 pour LibreHU service, FM et MCU Toolkit, dont le code natif
est ARM seulement), suit `screenshots/plan.json` (ouvrir, toucher un texte, capturer) et publie les PNG dans la
release `screenshots`. Ce sont les images des galeries des README.

## Construire

Action **Build** (artefact et release `latest-main`), ou `./gradlew :app:assembleDebug` avec le SDK Android.
