# Release process

1. **Version**: bump `versionCode` and `versionName` in `android/app/build.gradle`. Every version that
   users should get is a release: Obtainium and F-Droid follow the tags.
2. **Changelog**: `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` in all seven
   locales (en-US, it-IT, fr-FR, de-DE, es-ES, zh-CN, ja-JP), under 500 characters.
3. **Push to `main`**. CI (`.github/workflows/build.yml`) runs the unit tests, builds the debug APK, the signed release APK (`full` flavour) and the AAB (`play` flavour),
   and, when the `versionName` has no tag yet, creates the tag `vX.Y.Z` and the GitHub Release with
   `Songport-vX.Y.Z.apk` and `Songport-vX.Y.Z-play.aab`.
4. **Obtainium** users get it at once. **F-Droid** finds the new tag (`UpdateCheckMode: Tags`),
   builds it from source a few days later and, because the build is reproducible, publishes the
   GitHub APK with its own signature.

## Keeping the F-Droid build reproducible

- No build variable that changes the app in the GitHub build (`SPOTIFY_CLIENT_ID`,
  `GOOGLE_CLIENT_ID`, `LASTFM_API_KEY`, `APPLE_DEVELOPER_TOKEN`…): F-Droid builds without them.
- The release key never changes (certificate SHA-256
  `21eadf268c72f85565961a6045bf4be5abaf256d680d1e0d808d93ed22d35032`). Keep a backup of the keystore and
  its passwords outside GitHub.
- The F-Droid recipe is `metadata/com.xlollx.songport.yml` in fdroiddata; a copy lives in `fdroid/`.

## Google Play (when it happens)

The `play` flavour is published as **Trackhop**, like every other build: same package id, no built-in
web connectors, AdMob banner. Listing texts, Data safety answers and the closed-test kit are in `docs/play-store-listing.md`
and `docs/play-testers.md`.

## Checks before pushing

- Strings: the same names, in the same order, in all seven `values*/strings.xml`; apostrophes escaped
  (`\'`).

[All guide pages](./)
