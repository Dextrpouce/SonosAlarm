# Projet : gestion des alarmes Sonos (Era 100) depuis Android

## Faisabilité
- Les alarmes sont gérées via le service UPnP `AlarmClock` en local (port 1400, SOAP), sans cloud.
- Actions : `ListAlarms`, `CreateAlarm`, `UpdateAlarm`, `DestroyAlarm`.
- Référence de code : SoCo, `soco/alarms.py` (commit 5eb7fcd, dépôt https://github.com/SoCo/soco).

## Réserves
- Sonos migre progressivement vers une API REST locale ; l'UPnP peut être restreint par une mise à jour.
- Erreurs possibles à la sauvegarde (ex. UPnP 800), parfois liées au réseau Wi-Fi.
- Compatibilité Era 100 non confirmée : à valider sur l'enceinte.

- UPnP 801 à la création = conflit : même pièce, même minute de début, au moins un jour commun (ONCE entre en conflit avec tout). Vérifié sur l'Era 100 le 2026-11-11 ; 1 min d'écart ou des sonneries qui se chevauchent passent.

## Étapes
1. Lancer `test_alarme_era100.py <IP>` sur un ordinateur du réseau domestique (lister, créer, relire, supprimer une alarme de test).
2. Analyser la sortie ; ajuster si `ONCE` ou une autre valeur est refusée.
3. Si le test passe : porter la couche SOAP en Kotlin (OkHttp + parser XML), puis l'interface.

## État (2026-11-11)
- Test SoCo passé sur l'Era 100.
- App Android créée (Kotlin, Compose, Glance) : gestion des alarmes, écran de réveil +15/+30 min avec hausse du volume, widget, choix du son (carillon, favoris Sonos, lien Spotify).
- Connexion Spotify (Web API, PKCE) : liste des playlists avec pochettes ; Client ID saisi dans l'app, Redirect URI `ch.srs.sonosalarm://spotify-callback`.
- Pause vacances par alarme : désactivée après la dernière sonnerie avant le départ, réactivée après l'heure d'alarme du dernier jour off. Faite par le téléphone (WorkManager, Wi-Fi requis, nouvel essai toutes les 15 min).
- Secours téléphone : à l'heure de l'alarme, RingService vérifie GetTransportInfo (~10 s) ; si l'enceinte ne joue pas → sonnerie Android (USAGE_ALARM) puis tentative Spotify sur le téléphone via Web API (/me/player/play sur l'appareil du téléphone, scope user-modify-playback-state).
- Compile ; pas encore essayée sur un téléphone. Lecture Spotify par l'enceinte à valider (URI construite comme SoCo ShareLinkPlugin, compte `sn` repris des favoris Sonos).

## Fichiers
- `test_alarme_era100.py` : script de test minimal (SoCo).
