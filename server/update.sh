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
tar czf "$BACKUP_DIR/bigd-$(date +%F).tgz" -C "$SERVER_DIR" --exclude=data/tts --exclude=data/files --exclude=data/letter-papers --exclude=data/piper --exclude=data/cartoons --exclude=data/reels --exclude=data/aivideos --exclude=data/chat-files .env data 2>/dev/null && echo "backup saved"
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
if [ "$old" != "$new" ]; then
  if ! git diff --quiet "$old" "$new" -- server/package.json server/package-lock.json; then
    (cd "$SERVER_DIR" && npm install --omit=dev --no-audit --no-fund) && echo "libraries updated"
  fi
  pm2 restart soul-friend --update-env && echo "updated $old -> $new"
else
  echo "already up to date"
fi
