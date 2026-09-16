# Google Play listing – drafts and checklist

## Title (max 30)
Songport – playlist sync

## Short description (max 80)
Sync playlists across Spotify, Apple Music, YouTube Music, TIDAL and Deezer.

## Full description (English draft)
Keep your playlists identical across music services, without rebuilding them by hand.

- Pick a source playlist and a target one (or let the app create it)
- Run the sync whenever you want, or schedule it: hourly, every 6 hours, daily or weekly
- Additions only, or mirror removals too
- "Liked songs" as a source
- Paste the link of a public playlist and sync it into your library
- Import/export files (CSV, M3U, Apple Music/iTunes XML, JSON) for services without an API
- Preview before every sync: see what will be added or removed, then confirm
- Fix tracks that were not found yourself: search the target service, pick the right one, the app remembers it
- Tools: full playlist backup to files, duplicate removal
- "Share with Songport" from any music app: the link becomes a sync

Works with Spotify, Apple Music, YouTube Music, TIDAL (beta), Deezer (beta), personal
Navidrome/Subsonic, Jellyfin and Plex servers, Last.fm and ListenBrainz as sources, and files.

Free, no subscriptions, no in-app purchases. The app is supported by a single small banner ad.
No account to create: everything stays on your phone, access tokens are encrypted and never go
through external servers.

Scheduled syncs run on your phone, without servers: if the phone is off or battery saving blocks
the app, they resume at the first opportunity.

Note: Songport is not affiliated with Spotify, Apple, Google/YouTube, TIDAL or Deezer. Service names
are trademarks of their respective owners and are used only to indicate compatibility.

## Full description (Italian draft)
Tieni le tue playlist uguali su tutti i servizi musicali, senza rifarle a mano.

- Scegli una playlist di origine e una di destinazione (o lasciala creare all'app)
- Esegui la sincronizzazione quando vuoi, oppure programmala: ogni ora, 6 ore, giorno o settimana
- Solo aggiunte, oppure rispecchia anche le rimozioni
- "Brani preferiti" come origine
- Incolla il link di una playlist pubblica e sincronizzala nella tua libreria
- Import/export di file (CSV, M3U, XML di Apple Music/iTunes, JSON) per i servizi senza API
- Anteprima prima di ogni sync: vedi cosa verrà aggiunto o tolto e confermi
- I brani non trovati li sistemi tu: cerchi sul servizio di destinazione, scegli quello giusto e l'app se lo ricorda
- Strumenti: backup completo delle playlist in file, rimozione dei duplicati
- "Condividi con Songport" da qualsiasi app musicale: il link diventa una sync

Compatibile con Spotify, Apple Music, YouTube Music, TIDAL (beta), Deezer (beta), server personali
Navidrome/Subsonic, Jellyfin e Plex, Last.fm e ListenBrainz come sorgenti, e file.

Gratuita, senza abbonamenti né acquisti in-app. L'app si sostiene con un solo piccolo banner
pubblicitario. Nessun account da creare: tutto resta sul tuo telefono, i token di accesso sono
cifrati e non passano da server esterni.

Le sincronizzazioni programmate girano sul tuo telefono, senza server: se il telefono è spento o il
risparmio energetico blocca l'app, ripartono alla prima occasione utile.

Nota: Songport non è affiliata a Spotify, Apple, Google/YouTube, TIDAL o Deezer. I nomi dei servizi
sono marchi dei rispettivi proprietari e sono usati solo per indicare la compatibilità.

## Category
Music & Audio

## Content / Play Console questionnaires
- **Ads**: yes, contains ads (AdMob banner).
- **In-app purchases**: no. **Subscriptions**: no. The donation button opens an external page and unlocks nothing.
- **App access**: some features require third-party accounts and the user's own developer key
  (Spotify etc.). Give reviewers instructions: "Every feature can be exercised with playlist files
  without any account; music services require the reviewer's own free developer key, created through
  the in-app wizard."
- **Target audience**: 18+ (or 16+); not aimed at children.
- **Families policy**: not applicable.
- **Foreground services** (Android 14+): type `dataSync`, reason "user-initiated data transfer that
  may exceed background job limits"; fill in the declaration in Play Console.
- **Biometrics**: used only to unlock the app locally (BiometricPrompt); no biometric data is read or stored.

## Data safety – suggested answers
- Does the app collect or share data? **Yes** (through the ads SDK).
  - **Device or other IDs** → collected by the Google Mobile Ads SDK → purpose: advertising/marketing →
    shared with Google → optional (the user can refuse consent) → not processed ephemerally.
  - **App info and performance / diagnostics**: may be collected by the ads SDK → advertising.
- Data **not** collected by the developer: no account, e-mail, location, contacts, files.
- Data encrypted in transit: **yes** (HTTPS).
- Users can request deletion: **yes** (disconnect/uninstall; no data on developer servers).
- Privacy policy: URL of the published `docs/privacy-policy.md`.

## Release checklist
- [ ] `versionCode`/`versionName` bumped in `android/app/build.gradle`
- [ ] GitHub variables: `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `PRIVACY_POLICY_URL`, `KOFI_URL`. Service keys (`SPOTIFY_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `TIDAL_CLIENT_ID`, `DEEZER_APP_ID`, `LASTFM_API_KEY`) are optional: users create their own in the app
- [ ] Optional: GitHub secret `APPLE_DEVELOPER_TOKEN` (MusicKit JWT), to regenerate within 6 months; without it users paste their own token
- [ ] GitHub secrets: `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` (release) and `DEBUG_KEYSTORE_BASE64` (stable debug APK signature)
- [ ] Only if shipping a shared `GOOGLE_CLIENT_ID`: SHA-1 of the upload key **and** of Play App Signing registered in the Android OAuth client (never the debug key), and Google OAuth verification completed (`youtube` scope)
- [ ] AdMob app linked to the Play Store; GDPR message (UMP) published in AdMob → Privacy & messaging
- [ ] Privacy policy online and linked in the listing
- [ ] Screenshots (phone, 16:9 or 9:16, at least 2); 512×512 icon and 1024×500 feature graphic are in `docs/store/`
- [ ] Tested: Spotify login, Apple Music login (MusicKit WebView), manual sync, scheduled sync (wait 1 hour), import from public link, file import/export, GDPR consent with an EU VPN
