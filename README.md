# Soul Friend: floating puppy app

Soul Friend (ఆత్మ మిత్రుడు) is a cartoon puppy that floats on top of your phone screen. It talks with you in Telugu and English and does tasks for you.

## What the puppy can do

- **Talk:** tap the puppy and speak. It answers out loud and its face changes (happy, excited, thinking, sad, surprised, love, sleepy, confused).
- **Tasks:** reminders, timers, alarms, to-do list, notes, calls (opens the dialer), WhatsApp messages, opening apps, writing letters and translating.
- **Learn:**
  - It searches the internet for news, prices and results.
  - It answers from files you teach it on the owner-only Teach page.
  - It remembers each user (saved on that user's phone).
  - It reads photos you show it.

## Folders

- `server/` runs on your VPS (Hostinger).
  - `server.js` is the main server. `learn.js` handles teaching, knowledge and internet search. No extra packages are needed, only Node.js 20+.
  - `setup.sh` is the one-time setup. Run it with `bash setup.sh yourdomain.com`.
  - `.env.example` holds the settings: API key, `TEACH_PASSWORD`, `WEB_SEARCH`. Copy it to `.env`.
  - `public/teach.html` is the Teach page (`https://yourdomain/teach`).
  - `public/privacy.html` is the privacy policy. Fill in the [BRACKETS].
- `android/` is the Android app (Kotlin, no extra libraries). Open it in Android Studio, or upload it to GitHub to build automatically.
  - `app/src/main/java/com/soulfriend/pet/Store.kt` holds `DEFAULT_SERVER`. Put your server address there, or type it in the app.
  - `app/build.gradle.kts` holds `applicationId`. Change it to your own unique name before you publish.
  - `.github/workflows/build-apk.yml` makes GitHub build the APK for you.
- `store-images/` has the Play Store icon (512×512) and feature graphic (1024×500).

The step-by-step guide is in the "Soul Friend App Launch Guide" doc shared in the chat.
