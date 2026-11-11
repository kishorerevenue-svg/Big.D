# Habit AI brand

- `habit-ai-icon-512.png`: the app icon for Google Play (512 x 512). A gold biscuit (gold bar) with a bold stamped **H**, on deep navy.
- `habit-brain-icon-512.png`: the same for the optional Habit Brain app (purple, with a small 🧠).
- `habit-ai-board-1024x500.png`: the board / banner (also the Google Play "feature graphic" size), with the name, "Good habits are gold · మంచి అలవాట్లే బంగారం".

The phone icons (all sizes, adaptive icon with a themed-icon layer) are made from `src/`:

```
cd brand/src && node render.js      # needs Playwright (Chromium); writes everything to brand/src/out
```

Then copy `out/app/mipmap-*` to `android/app/src/main/res/`, `out/brain/mipmap-*` to `android/brain/src/main/res/`,
`out/app/drawable-nodpi/habit_logo.png` to `android/app/src/main/res/drawable-nodpi/` and `out/web/*.png` to `server/public/`.

The H is the Cinzel font (SIL Open Font License, `src/OFL-Cinzel.txt`); the Telugu line uses Hind Guntur (`server/fonts`).
