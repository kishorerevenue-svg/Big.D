#!/usr/bin/env bash
# Nightly job: get the latest Habit AI code from GitHub, restart if it changed,
# and keep a backup of settings and learned knowledge (last 7 nights).
# Installed by the one-line command in the guide; runs at 3:30 AM server time.
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SERVER_DIR/.." && pwd)"
BACKUP_DIR=/root/backups

echo "== $(date) =="
mkdir -p "$BACKUP_DIR"
tar czf "$BACKUP_DIR/bigd-$(date +%F).tgz" -C "$SERVER_DIR" --exclude=data/tts --exclude=data/files --exclude=data/letter-papers --exclude=data/piper --exclude=data/cartoons --exclude=data/reels --exclude=data/aivideos --exclude=data/chat-files --exclude=data/habit-gemma .env data 2>/dev/null && echo "backup saved"
ls -1t "$BACKUP_DIR"/bigd-*.tgz 2>/dev/null | tail -n +8 | xargs -r rm -f

# videos in Media need ffmpeg (installed once)
if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
  echo "installing ffmpeg for videos..."
  DEBIAN_FRONTEND=noninteractive apt-get update -y -q >/dev/null 2>&1
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q ffmpeg >/dev/null 2>&1 && echo "ffmpeg installed" || echo "ffmpeg install failed (will try again next time)"
fi

# our own cartoon maker needs python3 with Pillow (installed once)
if ! python3 -c "import PIL" >/dev/null 2>&1; then
  echo "installing Pillow for the cartoon maker..."
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q python3-pil >/dev/null 2>&1 || { DEBIAN_FRONTEND=noninteractive apt-get update -y -q >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y -q python3-pil >/dev/null 2>&1; }
  python3 -c "import PIL" >/dev/null 2>&1 && echo "Pillow installed" || echo "Pillow install failed (will try again next time)"
fi

cd "$REPO_DIR" || exit 1
old="$(git rev-parse HEAD)"
git pull -q || { echo "git pull failed"; exit 1; }
new="$(git rev-parse HEAD)"
if [ "$old" != "$new" ] && ! git diff --quiet "$old" "$new" -- server/package.json server/package-lock.json; then
  (cd "$SERVER_DIR" && npm install --omit=dev --no-audit --no-fund) && echo "libraries updated"
fi
# The Habit server must always run, also after the server itself restarted: PM2 brings back its saved list, or starts it.
revived=""
if ! pm2 describe soul-friend >/dev/null 2>&1; then
  revived=1
  pm2 resurrect >/dev/null 2>&1
  pm2 describe soul-friend >/dev/null 2>&1 || (cd "$SERVER_DIR" && pm2 start server.js --name soul-friend >/dev/null 2>&1)
  pm2 save >/dev/null 2>&1
  if pm2 describe soul-friend >/dev/null 2>&1; then echo "✅ the Habit server was stopped: it is running again"
  else echo "⚠️ couldn't start the Habit server. Send a screenshot of: pm2 logs soul-friend --lines 30"; fi
elif [ "$old" != "$new" ]; then
  pm2 restart soul-friend --update-env && echo "updated $old -> $new"
else
  echo "already up to date"
fi
# ...and it starts by itself whenever the server restarts (set up again when it had stopped: an old boot setup may point
# to an old Node or PM2 path)
if command -v systemctl >/dev/null 2>&1 && { [ -n "$revived" ] || ! systemctl is-enabled pm2-root >/dev/null 2>&1; }; then
  pm2 startup systemd -u root --hp /root >/dev/null 2>&1 && pm2 save >/dev/null 2>&1 && echo "✅ the Habit server now starts by itself after a server restart"
fi
