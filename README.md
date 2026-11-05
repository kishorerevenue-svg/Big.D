# Soul Friend: floating puppy app

Soul Friend (ఆత్మ మిత్రుడు) is a cartoon puppy that floats on top of your phone screen. It talks with you in Telugu and English and does tasks for you.

## What the puppy can do

- **Talk:** tap the puppy and speak. It answers out loud and its face changes (happy, excited, thinking, sad, surprised, love, sleepy, confused).
- **Tasks:** reminders, timers, alarms, to-do list, notes, calls (opens the dialer), WhatsApp messages, opening apps, writing letters and translating.
- **Learn:**
  - It searches the internet for news, prices and results.
  - It answers from files you teach it on the owner-only Teach page.
  - It remembers each user (saved on that user's phone).
  - It reads photos you show it, and it can look through the camera when you ask ("what's written on this board?").
- **Never without a brain:**
  - Claude answers first. If Claude is busy, a second Claude model tries, then Gemini (if `GEMINI_API_KEY` is set).
  - If none of them can answer, **our own brain** answers: a free, open AI model (Google Gemma) that runs on your own server with Ollama. It reads the news, Wikipedia, the weather and your Teach page first. It also answers when there is no Claude key or the day's budget is used up.
  - Common questions many people ask get a prepared answer at night, which is used at once next time (see the Teach page).
  - The phone's small built-in brain still sets reminders, alarms and timers, calls family and opens apps with no internet at all.
  - With no internet at all, the optional **BigD Brain** app (a tiny brain on the phone: Google's Gemma 4 E2B, run with LiteRT-LM) can talk too. It is a separate app so BigD AI stays small and installs on every phone; it needs a 64-bit phone with about 6 GB memory, and downloads its 2.6 GB model once.

## Folders

- `server/` runs on your VPS (Hostinger).
  - `server.js` is the main server. `learn.js` handles teaching, knowledge and internet search. No extra packages are needed, only Node.js 20+.
  - `setup.sh` is the one-time setup. Run it with `bash setup.sh yourdomain.com`.
  - `.env.example` holds the settings: API key, `TEACH_PASSWORD`, `WEB_SEARCH`. Copy it to `.env`.
  - `public/teach.html` is the Teach page (`https://yourdomain/teach`). It also shows our own brain and the learned answers.
  - `ownbrain.js` is our own brain (an open model run by Ollama on this server). `brain-setup.sh` installs it, choosing a model that fits the server's memory. The server runs it by itself once after an update; you can also run `true; /root/Big.D/server/brain-setup.sh` or press the button on the Teach page. Turn it off with `OWN_BRAIN=off` in `.env`.
  - `answers.js` learns answers to common questions (only general questions, never who asked). Turn it off with `LEARN_QUESTIONS=off`.
  - `appupdate.js` gives one-tap updates: every Lite build is published as a GitHub release, `/app/latest` tells the app about it, and `https://yourdomain/download` always gives the newest Lite APK (a link anyone can share).
  - `public/privacy.html` is the privacy policy. Fill in the [BRACKETS].
- `android/` is the Android app (Kotlin, no extra libraries). Open it in Android Studio, or upload it to GitHub to build automatically.
  - `app/src/main/java/com/soulfriend/pet/Store.kt` holds `DEFAULT_SERVER`. Put your server address there, or type it in the app.
  - `app/build.gradle.kts` holds `applicationId`. Change it to your own unique name before you publish.
  - `.github/workflows/build-apk.yml` makes GitHub build the APKs for you.
  - The app comes in two editions:
    - **Lite** (`BigD-AI-Lite.apk`, named "BigD AI Lite" on the phone): everything except "Reply to messages". Share this one on WhatsApp or a website.
    - **Full** (`BigD-AI-Full.apk` and the Play Store AAB): every feature. Upload the AAB to the Play Store.
    - Why: "Reply to messages" needs notification access. In India, Play Protect blocks any APK with notification access when it is installed from WhatsApp, a browser or a file manager. Apps from the Play Store are not blocked.
- `android/brain/` is BigD Brain, the optional tiny brain app (64-bit phones). Only apps signed with the same stamp can ask it. Its model comes from Hugging Face, or from `PHONE_BRAIN_URL` in the server's `.env`. `https://yourdomain/download/brain` gives the newest one.
- `store-images/` has the Play Store icon (512×512) and feature graphic (1024×500).

The step-by-step guide is in the "Soul Friend App Launch Guide" doc shared in the chat.
