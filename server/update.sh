#!/usr/bin/env bash
# Nightly job: get the latest BigD AI code from GitHub, restart if it changed,
# and keep a backup of settings and learned knowledge (last 7 nights).
# Installed by the one-line command in the guide; runs at 3:30 AM server time.
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SERVER_DIR/.." && pwd)"
BACKUP_DIR=/root/backups

echo "== $(date) =="
mkdir -p "$BACKUP_DIR"
tar czf "$BACKUP_DIR/bigd-$(date +%F).tgz" -C "$SERVER_DIR" .env data 2>/dev/null && echo "backup saved"
ls -1t "$BACKUP_DIR"/bigd-*.tgz 2>/dev/null | tail -n +8 | xargs -r rm -f

cd "$REPO_DIR" || exit 1
old="$(git rev-parse HEAD)"
git pull -q || { echo "git pull failed"; exit 1; }
new="$(git rev-parse HEAD)"
if [ "$old" != "$new" ]; then
  pm2 restart soul-friend --update-env && echo "updated $old -> $new"
else
  echo "already up to date"
fi
