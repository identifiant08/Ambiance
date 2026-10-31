# Ambiance

Vidéos d'ambiance YouTube (forêt sous la pluie, chalet, feu de bois…) sur le Xiaomi Mi Box S, pilotées depuis le téléphone.

- **Sur la TV** : écran « What's your mood today? », 10 ambiances prêtes, recherche libre de playlists et de vidéos longues, lecture en boucle, aléatoire, minuterie.
- **Sur le téléphone** : la même APK devient une télécommande (trouve la TV toute seule sur le Wi-Fi). Le bouton « Partager » de l'appli YouTube envoie une vidéo ou une playlist directement sur la TV.
- **Lecture** : yt-dlp (youtubedl-android) récupère les flux directs, ExoPlayer les lit en natif jusqu'en 1080p (4K en option). Pas de clé API, pas de publicité. yt-dlp se met à jour seul une fois par jour.

## Installer

GitHub Actions compile `Ambiance.apk` à chaque push sur `main` (onglet Releases).

Sur le Mi Box : installe l'appli **Downloader** depuis le Play Store, autorise-la à installer des applis inconnues, puis saisis l'adresse
`https://github.com/identifiant08/ambiance/releases/latest/download/Ambiance.apk`.

Sur le Pixel : ouvre la même adresse dans Chrome et installe.

## Structure

- `app/src/main/assets/web/` : interfaces TV (`tv.html`), télécommande (`remote.html`), recherche de TV (`finder.html`), scènes animées (`moods.js`)
- `Engine.kt` : file d'attente et ExoPlayer · `Resolver.kt` : yt-dlp · `YtSearch.kt` : recherche · `RemoteServer.kt` : serveur de télécommande (port 8765, mDNS `_ambiance._tcp`)
