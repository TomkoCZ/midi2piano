# MIDI → Piano (Android)

Android port skriptu `midi2piano.py`. Z libovolného MIDI vytvoří:
1. klavírní noty (náhled + export **PDF** a zdroj **.ly** pro LilyPond),
2. zvuk přehraný z notového zápisu (vlastní klavírní syntezátor; export **M4A/WAV**),
3. video „padající noty“ (Synthesia styl, 1280×720, úvod 5 s) v rychlostech 1× / 0,55× / 0,25× (export **MP4**).

UI: Jetpack Compose, Material 3, dynamické barvy (Android 12+), velké zaoblené tvary.

## Sestavení APK
**A) Android Studio:** File → Open této složky → Build → Build APK(s).
**B) Bez PC (GitHub Actions):** nahraj složku do repozitáře na GitHub, záložka Actions → Build APK → stáhni artefakt `app-debug.apk`.
**C) Příkazová řádka:** `gradle assembleDebug` (Gradle 8.9, JDK 17, Android SDK 35).

Výstup: `app/build/outputs/apk/debug/app-debug.apk`. minSdk 26.
