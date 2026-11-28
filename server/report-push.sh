#!/usr/bin/env bash
# Every hour: the server writes how it is doing into the owner's PRIVATE GitHub repo "habit-reports", so Claude can read it
# directly (Claude can't log in to the server). What goes there: health, brains, Claude use, office file COUNTS, studio and
# channel status, the last errors, the auto-update log, disk and memory. No office file text, no keys or passwords.
# Needs once: the private repo kishorerevenue-svg/habit-reports and a token for it:  bash set-key.sh REPORT_TOKEN
# Install once (as root):   bash /root/Big.D/server/report-push.sh --install
# Remove:                   bash /root/Big.D/server/report-push.sh --remove
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT=/root/habit-reports
REPO="${REPORT_REPO:-kishorerevenue-svg/habit-reports}"
LINE="17 * * * * /bin/bash $SERVER_DIR/report-push.sh >> /root/report-push.log 2>&1"

if [ "${1:-}" = "--install" ]; then
  (crontab -l 2>/dev/null | grep -v 'report-push.sh'; echo "$LINE") | crontab -
  echo "✅ Hourly report to GitHub is ON. Sending the first one now…"
  exec /bin/bash "$SERVER_DIR/report-push.sh"
fi
if [ "${1:-}" = "--remove" ]; then
  crontab -l 2>/dev/null | grep -v 'report-push.sh' | crontab -; echo "Hourly report is OFF."; exit 0
fi

val() { grep -E "^$1=" "$SERVER_DIR/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '"'"'"; }
TOKEN="$(val REPORT_TOKEN)"
[ -z "$TOKEN" ] && { echo "No REPORT_TOKEN yet: bash $SERVER_DIR/set-key.sh REPORT_TOKEN"; exit 1; }
PORT="$(val PORT)"; PORT="${PORT:-3000}"
ADMIN="$(val ADMIN_PASSWORD)"; TEACH="$(val TEACH_PASSWORD)"
URL="http://localhost:$PORT"

mkdir -p "$OUT"; cd "$OUT" || exit 1
[ -d .git ] || { git init -q; git checkout -q -b main 2>/dev/null; git config user.name "Habit server"; git config user.email "server@bigdai.tech"; }

# hide anything that looks like a key, a password or a phone/Aadhaar number before it is written
scrub() { sed -E -e 's/(sk-ant-|AIza|hf_|ghp_|github_pat_)[A-Za-z0-9_\-]+/\1***/g' -e 's/\b[0-9]{4}[ -]?[0-9]{4}[ -]?[0-9]{4}\b/#### #### ####/g' -e 's/(\+91[ -]?)?\b[6-9][0-9]{9}\b/##########/g'; }

{
  echo "Report: $(date '+%F %T %Z')"
  echo "Code: $(cd "$SERVER_DIR/.." && git log -1 --format='%h %s' | cut -c1-120)"
  echo; echo "## Health"; curl -s -m 10 "$URL/health"; echo
  echo; echo "## System"; uptime; free -m | head -2; df -h / | tail -1
  echo; echo "## PM2"; pm2 jlist 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{for(const p of JSON.parse(s))console.log(p.name,p.pm2_env.status,"restarts:",p.pm2_env.restart_time,"mem:",Math.round((p.monit||{}).memory/1048576)+"MB","cpu:",(p.monit||{}).cpu+"%")}catch(e){console.log("?")}})'
} > status.txt 2>&1

curl -s -m 20 -H "X-Admin-Key: ${ADMIN:-$TEACH}" "$URL/api/brains" > brains.json 2>/dev/null
curl -s -m 20 -H "X-Teach-Key: $TEACH" "$URL/api/teach/studio" | head -c 20000 > studio.json 2>/dev/null
curl -s -m 20 -H "X-Teach-Key: $TEACH" "$URL/api/teach/channels" > channels.json 2>/dev/null
node "$SERVER_DIR/office-report.js" > offices.txt 2>&1
pm2 logs soul-friend --err --lines 150 --nostream 2>/dev/null | scrub > errors.txt
pm2 logs soul-friend --out --lines 150 --nostream 2>/dev/null | scrub > output.txt
tail -n 60 /root/quick-update.log 2>/dev/null > auto-update.txt
crontab -l 2>/dev/null | grep -v '^#' > cron.txt

git add -A >/dev/null 2>&1
git commit -q -m "report $(date '+%F %H:%M')" >/dev/null 2>&1 || true
git -c credential.helper= push -q "https://x-access-token:${TOKEN}@github.com/${REPO}.git" HEAD:main 2>&1 | sed "s/${TOKEN}/***/g" | head -3
echo "$(date '+%F %T') report sent"
