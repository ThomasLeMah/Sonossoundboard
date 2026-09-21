# Sonos Soundboard

Une petite application Android qui sert de **soundboard** pour envoyer des effets
sonores (SFX) sur des enceintes **Sonos** connectées en Wi-Fi — idéalement
**par-dessus la musique en cours**, sans l'arrêter.

L'appli fonctionne **entièrement en local** sur ton réseau Wi-Fi : aucun compte
Sonos, aucun cloud, aucune connexion Internet requise pour jouer les sons.

---

## Comment ça marche

Une enceinte Sonos ne joue qu'un seul flux audio à la fois et ne lit jamais un
fichier local directement : elle télécharge une URL. L'appli combine donc deux
mécanismes, choisis automatiquement selon l'enceinte :

1. **audioClip** (enceintes récentes / S2 : One, Move, Roam, Beam, Arc, Five,
   Symfonisk…) — le son est joué **par-dessus** la musique avec baisse
   temporaire du volume (ducking), puis la musique reprend toute seule. C'est le
   comportement idéal d'un soundboard.
2. **Interruption / reprise** (repli, compatible **toutes** les Sonos) — l'appli
   mémorise le morceau en cours, joue le SFX, puis restaure et relance la
   musique. Il y a une brève coupure.

Pour servir les sons à l'enceinte, l'appli lance un mini-serveur HTTP sur le
téléphone (`http://<ip-du-téléphone>:<port>/clip/<id>`). Le téléphone et les
enceintes doivent donc être sur **le même réseau Wi-Fi**.

## Utilisation

1. Ouvre l'appli (téléphone connecté au même Wi-Fi que les Sonos).
2. Elle scanne le réseau et liste les enceintes trouvées — choisis-en une en
   haut de l'écran (bouton avec l'icône enceinte). Le bouton ↻ relance le scan.
3. Appuie sur **« Ajouter un son »** pour importer un fichier audio
   (MP3, WAV, OGG, M4A/AAC, FLAC) depuis le téléphone.
4. Tape sur un pad pour jouer le son sur l'enceinte sélectionnée.
5. **Appui long** sur un pad pour le renommer ou le supprimer.
6. Le curseur **Volume SFX** règle le volume du clip (mode audioClip).

## Récupérer l'APK (sans rien compiler)

Le dépôt compile l'APK automatiquement via **GitHub Actions** :

1. Va dans l'onglet **Actions** du dépôt GitHub.
2. Ouvre le dernier run **« Build APK »** (déclenché à chaque push, ou
   manuellement via *Run workflow*).
3. Télécharge l'artéfact **`sonos-soundboard-debug`** → il contient
   `app-debug.apk`.
4. Transfère l'APK sur ton téléphone Android et installe-le (autorise
   « sources inconnues » si demandé).

> C'est une APK *debug* signée avec la clé de debug — parfait pour un usage
> perso. Pour une distribution large, il faudrait la signer avec une clé de
> release.

## Compiler soi-même (optionnel)

Ouvre le projet dans **Android Studio** (Giraffe ou plus récent), laisse-le
synchroniser Gradle, puis *Run*. En ligne de commande :

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

## Détails techniques

- **Stack** : Kotlin, Jetpack Compose (Material 3), coroutines.
- **Découverte** : SSDP/UPnP (`urn:schemas-upnp-org:device:ZonePlayer:1`).
- **audioClip** : API de contrôle locale Sonos (`https://<ip>:1443/api/v1`,
  certificat auto-signé accepté uniquement pour le LAN).
- **Repli SOAP** : services UPnP `AVTransport` / `RenderingControl` sur le
  port 1400.
- **Serveur de clips** : NanoHTTPD embarqué.
- `minSdk 26` (Android 8.0+), `targetSdk 34`.

## Limites connues

- Le mode audioClip dépend du modèle d'enceinte et de la version du firmware
  Sonos. S'il est refusé, l'appli bascule automatiquement sur l'interruption /
  reprise.
- Le repli SOAP cible l'enceinte sélectionnée ; sur un groupe multi-pièces, il
  vaut mieux viser le coordinateur du groupe (le mode audioClip, lui, marche
  quelle que soit la configuration).
- Les fichiers audio très longs ne sont pas adaptés à un soundboard : privilégie
  des SFX courts.
