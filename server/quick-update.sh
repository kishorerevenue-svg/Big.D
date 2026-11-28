#!/usr/bin/env bash
# Every 30 minutes: takes Claude's newest server fixes from GitHub by itself (the owner no longer types update.sh each time).
# Safe: every changed server file is checked first; if a check fails, or the server doesn't answer after the restart,
# it goes back to the code that worked and stays there.
# Install once (as root):   bash /root/Big.D/server/quick-update.sh --install
# Remove:                   bash /root/Big.D/server/quick-update.sh --remove
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SERVER_DIR/.." && pwd)"
LOG=/root/quick-update.log
LINE="*/30 * * * * /bin/bash $SERVER_DIR/quick-update.sh >> $LOG 2>&1"

if [ "${1:-}" = "--install" ]; then
  (crontab -l 2>/dev/null | grep -v 'quick-update.sh'; echo "$LINE") | crontab -
  echo "✅ Auto-update is ON: every 30 minutes the server takes the newest fixes by itself (log: $LOG)"
  exec /bin/bash "$SERVER_DIR/quick-update.sh"
fi
if [ "${1:-}" = "--remove" ]; then
  crontab -l 2>/dev/null | grep -v 'quick-update.sh' | crontab -
  echo "Auto-update is OFF."; exit 0
fi

# only one at a time
exec 9>/tmp/habit-quick-update.lock
flock -n 9 || exit 0

cd "$REPO_DIR" || exit 1
git fetch -q origin main 2>/dev/null || { echo "$(date '+%F %T') fetch failed"; exit 0; }
old="$(git rev-parse HEAD)"; new="$(git rev-parse origin/main)"
[ "$old" = "$new" ] && exit 0
BAD=/root/.habit-bad-update
[ "$(cat $BAD 2>/dev/null)" = "$new" ] && exit 0          # this one failed before: wait for Claude's next fix
echo "== $(date '+%F %T') new fixes: ${old:0:7} -> ${new:0:7}"

# check every changed server .js file of the new code before using it
bad=""
for f in $(git diff --name-only "$old" "$new" -- 'server/*.js'); do
  [ -f "$f" ] || continue
  tmp="$(mktemp --suffix=.js)"
  git show "$new:$f" > "$tmp" 2>/dev/null && node --check "$tmp" >/dev/null 2>&1 || bad="$bad $f"
  rm -f "$tmp"
done
if [ -n "$bad" ]; then echo "⚠️ not taken (a file doesn't check):$bad"; echo "$new" > $BAD; exit 0; fi

# the full update (backup, tools, libraries, restart) as before
/bin/bash "$SERVER_DIR/update.sh" >/dev/null 2>&1

# does it answer?
port="$(grep -E '^PORT=' "$SERVER_DIR/.env" 2>/dev/null | tail -1 | cut -d= -f2)"
ok=""
for i in 1 2 3 4 5 6; do
  sleep 5
  curl -sf "http://localhost:${port:-3000}/health" >/dev/null 2>&1 && { ok=1; break; }
done
if [ -n "$ok" ]; then
  echo "✅ updated to ${new:0:7}, server answers"
else
  echo "❌ server didn't answer after ${new:0:7}: going back to ${old:0:7}"
  echo "$new" > $BAD
  git reset -q --hard "$old"
  pm2 restart soul-friend --update-env >/dev/null 2>&1
fi
