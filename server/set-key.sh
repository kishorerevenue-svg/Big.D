#!/usr/bin/env bash
# Saves one setting in .env and restarts the BigD AI server. Example:
#   bash /root/Big.D/server/set-key.sh GOOGLE_TTS_KEY AIzaSy...
# Use the same way to change the Claude key:  ... set-key.sh ANTHROPIC_API_KEY sk-ant-...
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
cd "$(dirname "$0")" || exit 1
name="$1"
value="$(printf '%s' "$2" | tr -d '[:space:]"'"'"'')"   # drop spaces and quotes that sneak in when pasting
if ! [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || [ -z "$value" ]; then
  echo "Usage: set-key.sh NAME VALUE   (for example: set-key.sh GOOGLE_TTS_KEY AIzaSy...)"; exit 1
fi
touch .env
{ grep -v "^$name=" .env || true; printf '%s=%s\n' "$name" "$value"; } > .env.new && mv .env.new .env && chmod 600 .env
echo "SAVED $name (ends with ...${value: -4})"
pm2 restart soul-friend --update-env > /dev/null && echo "server restarted"
sleep 3
port="$(grep -E '^PORT=' .env | tail -1 | cut -d= -f2)"
curl -s "http://localhost:${port:-3000}/health"; echo
