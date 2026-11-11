#!/usr/bin/env bash
# Saves one setting in .env and restarts the Habit AI server. Examples:
#   bash /root/Big.D/server/set-key.sh GOOGLE_TTS_KEY AIzaSy...
#   bash /root/Big.D/server/set-key.sh ANTHROPIC_API_KEY sk-ant-...   (a new Claude key)
#   bash /root/Big.D/server/set-key.sh GEMINI_API_KEY AIza...         (the backup AI, optional)
#   bash /root/Big.D/server/set-key.sh GEMINI_API_KEY --remove        (takes a setting out again)
# A Claude or Gemini key is tested with the company first. A key they say is wrong is NOT saved,
# so the key that is already there keeps working.
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
cd "$(dirname "$0")" || exit 1
name="$1"
value="$(printf '%s' "$2" | tr -d '[:space:]"'"'"'')"   # drop spaces and quotes that sneak in when pasting
if ! [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || [ -z "$value" ]; then
  echo "Usage: set-key.sh NAME VALUE    (for example: set-key.sh GOOGLE_TTS_KEY AIzaSy...)"
  echo "       set-key.sh NAME --remove (takes the setting out)"
  exit 1
fi
touch .env

restart() {
  pm2 restart soul-friend --update-env > /dev/null && echo "server restarted"
  sleep 3
  port="$(grep -E '^PORT=' .env | tail -1 | cut -d= -f2)"
  curl -s "http://localhost:${port:-3000}/health"; echo
}

if [ "$value" = "--remove" ]; then
  grep -v "^$name=" .env > .env.new; mv .env.new .env && chmod 600 .env
  echo "REMOVED $name"
  restart; exit 0
fi

# the example text from the instructions instead of the real key
if [[ "$value" =~ ^(your|YOUR|Your|\<|\.\.\.) ]] || [[ "$value" == *key-here* ]] || [[ "$value" == *KEY_HERE* ]] || [[ "$value" == *KEY-HERE* ]]; then
  echo "NOT SAVED: \"$value\" is the example text, not a key. Paste your real key at the end instead."
  exit 1
fi

# test a Claude or Gemini key with the company before saving it
code=skip
case "$name" in
  ANTHROPIC_API_KEY) code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 https://api.anthropic.com/v1/models -H "x-api-key: $value" -H 'anthropic-version: 2023-06-01')" ;;
  GEMINI_API_KEY)    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 https://generativelanguage.googleapis.com/v1beta/models -H "x-goog-api-key: $value")" ;;
esac
case "$code" in
  skip) ;;
  200) echo "KEY WORKS: the company accepted it" ;;
  400|401|403)
    echo "NOT SAVED: the company says this key is not valid (error $code). Check that you copied the whole key."
    [ "$name" = "GEMINI_API_KEY" ] && echo "For Gemini, also check that the key's project has billing on."
    exit 1 ;;
  *) echo "Couldn't test the key right now (answer: $code). Saving it anyway." ;;
esac

{ grep -v "^$name=" .env || true; printf '%s=%s\n' "$name" "$value"; } > .env.new && mv .env.new .env && chmod 600 .env
if [ ${#value} -le 6 ]; then echo "SAVED $name = $value"; else echo "SAVED $name (ends with ...${value: -4})"; fi
restart
