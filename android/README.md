# Ghostream for Android

Ghostream for **Android 8.0 (API 26) through Android 17 (API 37)**: fast file downloads, video sites, torrents and a built-in browser with ad blocking.

## What it does

- **Fast file downloads** in parallel parts (HTTP Range requests). When a part finishes early, its connection takes over half of the largest part still left.
- **Pause and resume**: a download continues after closing the app, restarting the phone or losing the connection. If the file on the server changed, it starts over instead of mixing old and new data.
- **Queue** with background downloads, a notification and a "Pause all" button.
- **Sorted folders**: files are saved in `Download/Ghostream/`, in ZIP, DOC, Programs, Videos, Music, Torrent, Images and Other folders (sorting can be turned off). If that folder can't be written, the app's own `Android/data/…/files/Download/Ghostream/` is used.
- **Video sites** through yt-dlp (MP4, MKV, or audio only as M4A or MP3), **torrents** through libtorrent, and a **browser** that finds videos on a page and blocks ads and trackers with uBlock Origin's lists, EasyList, EasyPrivacy, Peter Lowe's list and Ghostream's own list (`rules/trackers.json`).
- **Privacy**: HTTP or SOCKS5 proxy, Do Not Track / Global Privacy Control, tracking tags removed from links, and a kill switch that runs torrents only while a VPN is on.

### Free and Ghostream Turbo

| | Free | Turbo |
|---|---|---|
| Parts per file | up to 8 | up to 32 |
| Downloads at once | up to 3 | up to 10 |
| Torrent connections | up to 200 | up to 1000 |
| Video quality | up to 1080p | 2K, 4K and 8K where the site has them |
| Adding links | from the Browser tab | paste or share any link |

Turbo is unlocked with a key; see the [Turbo page](https://janardanpal007.github.io/Ghostream/#turbo).

## Installing

Download an APK from the [Releases page](https://github.com/Janardanpal007/Ghostream/releases/latest):

| File | For |
|---|---|
| `Ghostream-<version>-android-arm64.apk` | Almost every phone from 2017 on (smaller download) |
| `Ghostream-<version>-android-universal.apk` | Any device, including 32-bit phones, Chromebooks and emulators |

Open it and allow installing from your browser or file manager when Android asks. Allow notifications so downloads show in the background. Android 8 to 10 ask once for storage permission.

## Building from source

You need JDK 17 or newer and the Android SDK with platform 37 (Android Studio installs it). The app's version is `fdVersion` in `android/gradle.properties`.

`android/rules/trackers.json` (Ghostream's own ad and tracker list) is required: the build stops if neither `android/rules/` nor `../extension/rules/` contains it.

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/ (needs the release key, see below)
```

Each build makes two APKs: `app-arm64-v8a-<type>.apk` and `app-universal-<type>.apk`. Debug builds install beside the release app (`com.ghostream.app.debug`).

### Release signing

A release build stops with an error unless a release key is set; it is never signed with the debug key. Updates must always be signed with the same key, so keep it **outside** the project and back up the `.jks` file and its passwords.

Either set environment variables:

```bash
export FD_KEYSTORE=/path/to/ghostream-release.jks
export FD_KEYSTORE_PASSWORD=...
export FD_KEY_ALIAS=...
export FD_KEY_PASSWORD=...
```

or create `android/keystore.properties` (ignored by git); an environment variable wins over the file:

```properties
storeFile=/path/to/ghostream-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Where things are

Under `app/src/main/java/com/fastdownloader/app/`:

| Part | Where |
|---|---|
| Download queue, saving, folders | `engine/Engine.kt` |
| Segmented HTTP downloads | `engine/HttpTask.kt` |
| Torrents (libtorrent4j) | `engine/TorrentTask.kt` |
| Video sites (youtubedl-android) | `engine/VideoTask.kt` |
| Ad and tracker blocking | `engine/Filters.kt`, `engine/AdBlock.kt` |
| Turbo limits and keys | `engine/Turbo.kt` |
| HTTP client, proxy, privacy headers | `engine/Net.kt` |
| Screens (Jetpack Compose) | `ui/` |

## Licence

GNU General Public License v3 ([LICENSE](LICENSE)), because it includes [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0). Third-party components are listed in [NOTICE.md](NOTICE.md).
