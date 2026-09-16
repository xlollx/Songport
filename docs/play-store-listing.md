# Scheda Google Play – bozze e checklist

## Titolo (max 30)
Songport – sync playlist

## Descrizione breve (max 80)
Sincronizza le playlist tra Spotify, Apple Music, YouTube Music, TIDAL e Deezer.

## Descrizione completa (bozza IT)
Tieni le tue playlist uguali su tutti i servizi musicali, senza rifarle a mano.

• Scegli una playlist di origine e una di destinazione (o lasciala creare all'app)
• Esegui la sincronizzazione quando vuoi, oppure programmala: ogni ora, 6 ore, giorno o settimana
• Solo aggiunte, oppure rispecchia anche le rimozioni
• "Brani preferiti" come origine
• Incolla il link di una playlist pubblica e sincronizzala nella tua libreria
• Import/export di file (CSV, M3U, XML di Apple Music/iTunes, JSON) per i servizi senza API
• Anteprima prima di ogni sync: vedi cosa verrà aggiunto o tolto e confermi
• I brani non trovati li sistemi tu: cerchi sul servizio di destinazione, scegli quello giusto e l'app se lo ricorda
• Strumenti: backup completo delle playlist in file, rimozione dei duplicati
• "Condividi con Songport" da qualsiasi app musicale: il link diventa una sync

Compatibile con Spotify, Apple Music, YouTube Music, TIDAL (beta), Deezer (beta), server personali Navidrome/Subsonic, Jellyfin e Plex, Last.fm e ListenBrainz come sorgenti, e file.

Gratuita, senza abbonamenti né acquisti in-app. L'app si sostiene con un solo piccolo banner
pubblicitario. Nessun account da creare: tutto resta sul tuo telefono, i token di accesso sono
cifrati e non passano da server esterni.

Le sincronizzazioni programmate girano sul tuo telefono, senza server: se il telefono è spento o il
risparmio energetico blocca l'app, ripartono alla prima occasione utile.

Nota: Songport non è affiliata a Spotify, Apple, Google/YouTube, TIDAL o Deezer. I nomi dei servizi
sono marchi dei rispettivi proprietari e sono usati solo per indicare la compatibilità.

## Categoria
Musica e audio

## Contenuti / questionari Play Console
- **Annunci**: SÌ, contiene annunci (banner AdMob).
- **Acquisti in-app**: no. **Abbonamenti**: no.
- **Accesso app**: alcune funzioni richiedono l'accesso a account di terzi (Spotify ecc.). Fornire ai
  revisori istruzioni: "Collegare un account Spotify di prova; la funzione CSV è utilizzabile senza account."
- **Target audience**: 18+ (o 16+); non rivolta a bambini.
- **Norme sulle famiglie**: non applicabile.
- **Servizi in primo piano** (Android 14+): tipo `dataSync`, motivo "trasferimento dati avviato dall'utente
  che puo' superare i limiti dei lavori in background"; compilare la dichiarazione in Play Console.
- **Biometria**: usata solo per sbloccare l'app in locale (BiometricPrompt); nessun dato biometrico viene letto o salvato.

## Sicurezza dei dati (Data safety) – risposte suggerite
- Raccoglie o condivide dati? **Sì** (tramite SDK annunci).
  - **ID dispositivo o altri ID** → raccolto dall'SDK Google Mobile Ads → scopo: Pubblicità/marketing →
    condiviso con Google → facoltativo (l'utente può negare il consenso) → non è trattato in modo effimero.
  - **Dati sull'app e sulle prestazioni / diagnostica**: possono essere raccolti dall'SDK annunci → Pubblicità.
- Dati **non** raccolti dallo sviluppatore: nessun account, e-mail, posizione, contatti, file.
- Dati cifrati in transito: **Sì** (HTTPS).
- L'utente può richiedere la cancellazione: **Sì** (disconnessione/disinstallazione; nessun dato sui server dello sviluppatore).
- Informativa privacy: URL della pagina `docs/privacy-policy.md` pubblicata (GitHub Pages).

## Checklist prima dell'invio
- [ ] `versionCode`/`versionName` aggiornati in `android/app/build.gradle`
- [ ] Variabili GitHub: `SPOTIFY_CLIENT_ID`, `GOOGLE_CLIENT_ID`, (`TIDAL_CLIENT_ID`, `DEEZER_APP_ID`, `DEEZER_REDIRECT_URL`), `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `PRIVACY_POLICY_URL`, `KOFI_URL`
- [ ] Secret GitHub `APPLE_DEVELOPER_TOKEN` (JWT MusicKit) — da rigenerare entro 6 mesi
- [ ] Secrets GitHub: `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` (release) e `DEBUG_KEYSTORE_BASE64` (APK debug con firma stabile)
- [ ] SHA‑1 della chiave di upload **e** di Play App Signing registrate nel client OAuth Google (Android). Non registrare la chiave di debug se il repository è pubblico.
- [ ] Verifica OAuth Google completata (scope `youtube`), video dimostrativo se richiesto
- [ ] App AdMob collegata al Play Store; messaggio GDPR (UMP) pubblicato in AdMob → Privacy e messaggistica
- [ ] Privacy policy online e linkata nella scheda
- [ ] Screenshot (telefono 16:9 o 9:16, min 2); icona 512×512 e grafica in evidenza 1024×500 già in `docs/store/`
- [ ] Testato: login Spotify, login Apple Music (WebView MusicKit), sync manuale, sync programmata (attendere 1 ora), import da link pubblico, import/export file, consenso GDPR con VPN UE
