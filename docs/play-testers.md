# Google Play closed test – tester kit

A personal developer account created after 13 November 2023 can publish to production only after a
closed test with **12 testers opted in for 14 consecutive days** (the console shows the current
number; it was 20 until 2024). Testers are real people with a Google account who install the app
from Play and keep it installed; nothing else counts and nothing can replace them.

## Setting it up (console side, ten minutes)

1. Testing › Closed testing › create track "Beta" (or use the default one); upload the `play` AAB.
2. Testers tab: pick **Google Groups** and create a group at groups.google.com, e.g.
   `trackhop-beta@googlegroups.com`, with "Who can join" = *Anyone on the web can ask*, so that a
   tester joins by themself and you only approve. E-mail lists work too, but every address has to be
   typed by hand and testers often use a different one than they think.
3. Save: the console gives an **opt-in link** (`play.google.com/apps/testing/com.xlollx.songport`).
   A tester opens it with the Google account of their phone, taps *Become a tester*, then installs
   from Play. Both steps are needed; the opt-in alone is not an install.
4. The 14 days start when the twelfth tester is in. The counter on the dashboard is the truth: check it
   every couple of days, people drop out by uninstalling.

## Where the testers come from

- People who already use the GitHub or F-Droid build (issues, stars, the F-Droid MR followers): they
  must uninstall Songport first, it is the same package id, and the data does not carry over unless
  they export a backup from Files first.
- Friends and colleagues: the app is useful to anyone with two music services; a 30-second favour.
- Tester exchanges, where developers in the same situation test each other's apps:
  r/AndroidClosedTesting (Reddit), the "Play Console testers" groups on Telegram and Discord.
  Expect to install a dozen apps in return; keep them 14 days as you ask of others.
- A line in the README and a pinned GitHub Discussion with the opt-in link (texts below).

## Announcement texts

Replace `OPT-IN-LINK` with the real link.

### Italiano
> **Cercasi 12 tester per Trackhop su Google Play.** Trackhop (Songport su GitHub/F-Droid) sincronizza
> le playlist tra Spotify, Apple Music, YouTube Music, TIDAL, Deezer, server personali e file. È gratuita
> e open source; Google chiede 12 tester per 14 giorni prima di poterla pubblicare. Basta aprire
> OPT-IN-LINK con l'account Google del telefono, toccare "Diventa tester", installare da Play e tenerla
> installata due settimane. Usarla è gradito, non obbligatorio: qualsiasi problema va in
> github.com/xlollx/Songport/issues. Grazie!

### English
> **Looking for 12 testers for Trackhop on Google Play.** Trackhop (Songport on GitHub/F-Droid) keeps
> playlists in sync across Spotify, Apple Music, YouTube Music, TIDAL, Deezer, personal servers and
> files. Free and open source; Google requires 12 testers for 14 days before it can be published.
> Open OPT-IN-LINK with the Google account of your phone, tap "Become a tester", install from Play and
> keep it for two weeks. Using it is welcome, not required: anything odd goes to
> github.com/xlollx/Songport/issues. Thank you!

### Short (chat / tester exchanges)
> Trackhop – playlist sync between music services, open source, no account needed. Opt-in:
> OPT-IN-LINK. Happy to test yours back, send the link.

## What to tell testers to try (optional, makes the final questionnaire easier)

1. Files › Import a CSV or M3U, then a sync from that file to a service they have.
2. A sync between two services, with the preview.
3. One scheduled sync left running overnight.
4. The playlist generator with a free Gemini key, if they feel like it.

## "Apply for production" – the questionnaire

After the 14 days the dashboard shows *Apply for production*. Google asks, in free text:

- **How easy was it to recruit testers?** Honest answer: existing users of the GitHub and F-Droid
  builds, plus people from tester exchanges.
- **Does the app work as described? Did testers hit problems?** Say what came in on GitHub issues
  during the test and the commit or version that fixed it; "no issues" is accepted but reads worse
  than two fixed bugs.
- **How did you collect feedback?** GitHub issues and the in-app "Report an issue" button (Diagnostics),
  which opens a pre-filled issue.
- **Is the app ready for production?** Yes, with the version you intend to upload.

Google answers within about a week. A refusal names the reason and the test can be extended rather
than restarted.
