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
| **Fiche d'une appli** | en-tête (icône, paquet, dépôt, état), actions (Ouvrir, Réinstaller, Journal, Installer dans /system, Ne pas mettre à jour, **Désinstaller** : /data, /system ou les deux), avancement, versions dans /data et /system, version GitHub, branche, date, taille, nouveautés et commits, **toutes les versions** (chargées à la demande : une requête GitHub) à installer, revenir à une plus ancienne ou mettre dans /system |
| **Zips** | deux onglets : *Zips flashables* et *Fichiers de configuration* |
| **Téléchargements** (badge : ceux en cours) | en cours ou en pause (avancement, débit, temps restant, pause / reprise / annulation), terminés ou en échec, puis l'**historique** |
| **Réglages** | sections Général (édition, root), Vérifications, Installation, /system, Sources (jeton GitHub, index des zips), Stockage, À propos |

Les choix qu'une installation attend (autre signature, version plus ancienne, emplacement dans /system) s'ouvrent
quelle que soit la page affichée. Le code de l'interface est rangé par page (`ui/apps`, `ui/updates`, `ui/details`,
`ui/downloads`, `ui/zips`, `ui/settings`, composants communs dans `ui/components`) ; les couches données (`data/`) et
installation (`install/`) n'ont pas changé.

## Fonctions
- **Configuration rapide** : au premier lancement (et depuis Réglages → *Configuration rapide*), en quatre étapes :
  l'édition (LibreHU service, ivi-services de Jancar ou générique), le root (installations silencieuses), les applis
  (toutes celles du catalogue pour l'édition sont cochées, celles installées et à jour ne sont pas listées), puis leur
  installation avec l'avancement de chacune. *Passer* la ferme ; tout reste modifiable ensuite.
- **Compilation** (onglet) : comme LibreHU-service → Tweaks. Android 9 installe une appli hors Play Store en
  `speed-profile`, qui sans profil revient à `verify` (pas de code natif), et ne compile qu'appareil inactif et en
  charge, ce qui n'arrive presque jamais sur un autoradio. L'onglet liste les applis du catalogue installées et les
  applis ajoutées, avec leur filtre lu dans `dumpsys package dexopt` ; compiler une appli ou toutes
  (`cmd package compile -m speed -f`, root), une fois par version installée ; interrupteur par appli. **Automatique**
  (une minute après chaque démarrage et à l'ouverture, `CompileJob`) **seulement sans LibreHU-service**, qui le fait
  déjà lui-même : avec lui, l'onglet sert à la demande.
- **Hors ligne** : la liste des applis et leurs dernières versions est gardée après chaque vérification. Sans réseau,
  elle s'affiche telle quelle (« Pas de réseau : liste du … ») sans requête ratée ; avec réseau, chaque ouverture de
  LibreHU Apps vérifie à nouveau, ainsi qu'une fois après chaque démarrage de l'autoradio (dès que le réseau est là),
  et l'appli revérifie seule quand le réseau revient.
- **Redémarrer** (bouton marche de la barre du haut, root) : Android, ou dans TWRP.

- **Catalogue** : [catalog.json](catalog.json) de ce dépôt (téléchargé à chaque vérification), avec une copie
  intégrée à l'appli quand GitHub n'est pas joignable. Pour ajouter une appli : une entrée dans ce fichier. Il
  comprend les forks LibreHU d'Open Headunit (« Android Auto », `LibreHU/LibreHU-open-headunit-app`) et de
  xcertplay (« CarPlay », `LibreHU/LibreHU-xcertplay-app`, branche `master`), **LibreHU Helper** (diagnostic pour
  porter LibreHU sur d'autres autoradios, `LibreHU/LibreHU-helper-app`), et **KernelSU Next** (gestionnaire du root du noyau LibreHU), pris
  sur son dépôt officiel `KernelSU-Next/KernelSU-Next` : `"tagged": true` (releases classiques, la plus récente
  hors pré-releases) et `"asset"`, expression régulière de l'APK à prendre (l'APK normal, pas le `-spoofed`), dont le
  premier groupe donne le `versionCode` (`KernelSU_Next_v3.4.2_33355-release.apk` → 33355) quand les notes de la
  release n'en donnent pas. Signé par ses auteurs, pas avec la clé LibreHU.
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
  - **Par TWRP** (secours) : si Android n'arrive pas à écrire /system, LibreHU Apps prépare un petit zip (APK,
    liste blanche des permissions, ancienne copie retirée), l'ajoute à OpenRecoveryScript ; un seul redémarrage dans
    TWRP écrit toutes les installations en attente (bandeau « Redémarrer dans TWRP »), vérifie les APK et relance
    Android. Choix « Par TWRP » dans Réglages › /system, ou automatique après un échec d'écriture.
  - **Flash groupé par TWRP** (bouton « Flasher dans /system (TWRP) » de la barre du haut) : plusieurs applis cochées
    d'un coup (raccourcis : applis système, mises à jour, aucune), chacune avec sa version actuelle : mise à jour,
    réinstallation de la même version (réparation) ou première installation, en priv-app ou app selon son plan.
    Toutes écrites au même redémarrage dans TWRP, LibreHU Apps comprise (en dernier de la liste).
  - **Écrire dans /system** : comme `adb remount` : le vrai bloc (`by-name/system`) est rendu inscriptible
    (`blockdev --setrw`), puis le montage qu'Android utilise (`/` en system-as-root, qui apparaît comme `/dev/root`)
    est remonté en écriture sur place, gardé seulement si un fichier de test s'écrit ; remis en lecture seule ensuite.
    `mount -o rw,remount /system` seul ne marche pas (il vise `/dev/root`, un nœud qui n'existe pas). En secours (et
    avec Magisk, dont le tmpfs sur `/system/priv-app` perdrait l'écriture), une seconde montée privée du bloc ; sur
    l'UJC201 elle donnait des erreurs d'E/S là où `adb remount` marche. Refusé si la partition est sous dm-verity
    (bloc esclave d'un `dm-*`) : l'appli l'indique avec la commande à lancer soi-même
    (`fastboot --disable-verity flash vbmeta vbmeta.img`) et propose le module ou TWRP. Pour retirer : bouton
    **Désinstaller › /system** (voir plus bas).
  - **Aussi dans /data** (Réglages › /system, activé par défaut) : chaque installation ou mise à jour dans /system
    (automatique, à la main, par TWRP, groupée) installe aussi le même APK dans /data, en root : la nouvelle version
    tourne tout de suite et reste une mise à jour de la copie /system. Pas quand l'appli de /data est signée avec une
    autre clé. LibreHU Apps elle-même : installée dans /data en dernier (son processus se termine là) ; par TWRP à la
    main, elle n'y va pas (le redémarrage en recovery arriverait après la fin de son processus) : au démarrage, Android
    retire de lui-même une copie /data plus ancienne que celle de /system.
- **Désinstaller** (fiche de l'appli) : seulement les endroits où l'appli est :
  - **/data** : l'appli et ses données (`pm uninstall` en root, sinon la demande d'Android) ; pour une appli système
    mise à jour, seule la mise à jour part et la version de /system reprend la main ;
  - **/system** (root) : retirée au prochain redémarrage : module `librehu_<paquet>` marqué `remove` (le gestionnaire
    root le supprime au démarrage ; une copie d'origine masquée par `.replace` réapparaît), sinon dossier et liste
    blanche `privapp-permissions` effacés de la partition (même écriture que ci-dessus), sinon, quand la mémoire
    refuse l'écriture, un zip TWRP mis en attente (bandeau « Redémarrer dans TWRP »). Sous dm-verity rien n'est
    écrit (la raison est affichée). Une copie dans /data reste, comme appli normale. Une appli d'origine de la ROM
    retirée ne revient qu'en reflashant /system ;
  - **les deux** : /system d'abord, puis /data ; plus rien après le redémarrage.
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
  seulement / installateur Android), compilation après installation (speed, une appli à la fois),
  téléchargements simultanés, garder ou supprimer les APK téléchargés, **jeton GitHub** facultatif (5000 requêtes
  par heure au lieu de 60, envoyé seulement à api.github.com), **historique** des installations, configurations et
  flashs (date, versions avant → après, résultat) dans la page Téléchargements.
- **Mise à jour automatique dans /system** (Réglages, root, désactivée par défaut) : les mises à jour (manuelles,
  « Tout mettre à jour », arrière-plan) vont dans `/system/priv-app` ou `/system/app` selon le besoin de l'appli,
  pour les applis déjà dans /system ou pour toutes ; par module root, par écriture dans /system, ou automatique
  (module si le gestionnaire root le monte, sinon écriture, et module en secours si /system reste en lecture seule).
  Option « aussi dans /data » (activée, vaut pour toutes les installations dans /system) : la nouvelle version tourne
  tout de suite, /system l'a au démarrage suivant. Si /system refuse, l'installation normale dans /data se fait à la place (noté dans l'historique). LibreHU Apps
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
  lecture seule (/system, /vendor, /jancar…) est écrite par la même méthode que « Écrire dans /system » (remontage en place façon adb remount, sinon montage privé),
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
  - **Écrire dans `<partition>` maintenant** (zips accompagnés d'une image : TWRP, noyaux LibreHU) : sans passer par la
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

## Root (SuperSU) : demandé une fois pour toutes

Cette appli a besoin du root par SuperSU (installé dans la ROM) ; chaque demande doit être accordée avec « Se
souvenir ». Pour que toutes les fenêtres SuperSU arrivent au même moment, l'assistant de premier démarrage de LibreHU
Launcher (« Autoriser le root pour toutes les applis LibreHU ») interroge chaque appli LibreHU l'une après l'autre :
`RootRequestReceiver`, sur `org.librehu.action.REQUEST_ROOT` envoyé à ce paquet, lance une fois `su -c id` (SuperSU
affiche alors la demande de cette appli), puis répond `org.librehu.action.ROOT_RESULT` (extras `package`, `granted`)
au paquet indiqué dans `reply_to`. Rien d'autre que `id` n'est lancé ; une seconde demande dans les 20 s est ignorée.

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

## Vérifier que toutes les mises à jour sont visibles

`python3 tools/check_releases.py` : pour chaque appli de `catalog.json` et chaque branche publiée, vérifie que
`latest-<branche>` a une `release.txt` lisible avec un `versionCode` et qu'elle a été construite depuis le dernier
commit de la branche (sinon la CI a échoué ou tourne encore : LibreHU Apps montre l'ancienne version). Pour les applis à
éditions (`librehu-service`, `ivi`, `main`), signale aussi une branche d'édition à laquelle manquent des commits de
`main` : un correctif poussé seulement sur `main` n'arrive dans cette édition qu'une fois `main` fusionnée dedans. La
mise à jour est proposée quand le `versionCode` publié dépasse celui installé (nombre de commits de la branche).

## Construire

Action **Build** (artefact et release `latest-main`), ou `./gradlew :app:assembleDebug` avec le SDK Android.

## Optimisation (R8)

L'APK publié par la CI (`assembleRelease`) est **optimisé par R8** : code et ressources réduits et optimisés, ce qui
rend Jetpack Compose nettement plus fluide ([Compose performance](https://developer.android.com/develop/ui/compose/performance)),
**sans renommage** (`-dontobfuscate` dans `app/proguard-rules.pro`) : les traces des journaux restent lisibles. Les
règles gardent aussi ce qui n'est appelé que par son nom (méthodes natives, helpers root lancés par `app_process`). Si
une fonction plante avec « class / method not found », regarder `usage.txt` dans l'artefact *R8 reports* de la CI (ce
que R8 a retiré) et ajouter une règle `-keep`.
