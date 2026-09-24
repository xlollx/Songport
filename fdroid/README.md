# F-Droid

Songport's GitHub build (`full` flavor) is F-Droid compatible: only free libraries (AndroidX, Kotlin,
OkHttp, Tink), no ads SDK, no trackers, no prebuilt binaries in the repository, no self-updater. It
includes the web connectors (`android/bridge`), which use the services' web interfaces rather than
official APIs: that is what the NonFreeNet anti-feature describes. The `play` flavor carries the ads
SDK and is never built by F-Droid.

What F-Droid reads from this repository:

- `fastlane/metadata/android/<locale>/`: title, short and full description, changelog per
  `versionCode`, icon and feature graphic. Phone screenshots go in `images/phoneScreenshots/`.
- `.github/FUNDING.yml`: donation links.
- `android/gradle/wrapper/`: the Gradle version (the wrapper JAR is the official 8.14.3 one,
  SHA-256 `7d3a4ac4…6172`, as listed on gradle.org).

## Submitting

1. Create an account on gitlab.com and fork https://gitlab.com/fdroid/fdroiddata.
2. Add `com.xlollx.songport.yml` from this folder as `metadata/com.xlollx.songport.yml`.
3. Open a merge request with the "App inclusion" template. The CI of fdroiddata builds the app; the
   reviewers comment there if something needs changing.

The recipe must stay exactly as `fdroid rewritemeta` writes it, or fdroiddata's CI fails: no
comments, Unix line endings (LF). Pasting it into GitLab's web editor on Windows saves CRLF; upload
the file with "Replace" instead. The `prebuild` line drops the `playImplementation` dependencies
(the ads SDK of the Play flavor, never built by F-Droid) so the scanner does not flag them.

Check a change locally with `pip install fdroidserver`, then `fdroid rewritemeta` and `fdroid lint`
in a folder holding `metadata/com.xlollx.songport.yml`.

After inclusion F-Droid picks up new versions by itself from the `vX.Y.Z` tags.

## Signing

F-Droid signs its build with its own key, different from the GitHub one: updating between the GitHub
and the F-Droid version needs an uninstall first (data is lost). The web connectors are built into the
`full` flavor, so the F-Droid version does not depend on the separate Bridge app or its certificate
list. Reproducible builds would let F-Droid ship the GitHub-signed APK instead; that needs the GitHub
CI build to be bit-for-bit repeatable without the repository variables.
