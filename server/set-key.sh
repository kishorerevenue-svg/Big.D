#!/usr/bin/env bash
# Saves one setting in .env and restarts the Habit AI server. Examples:
#   bash /root/Big.D/server/set-key.sh GOOGLE_TTS_KEY AIzaSy...
#   bash /root/Big.D/server/set-key.sh ANTHROPIC_API_KEY sk-ant-...   (a new Claude key)
#   bash /root/Big.D/server/set-key.sh GEMINI_API_KEY AIza...         (the backup AI, optional)
#   bash /root/Big.D/server/set-key.sh HF_TOKEN hf_...                (Hugging Face: brings Habit's own trained model)
#   bash /root/Big.D/server/set-key.sh GEMINI_API_KEY --remove        (takes a setting out again)
# Safest: give only the name, then paste the key when it asks. It isn't shown on the screen, so a screenshot can't show it:
#   bash /root/Big.D/server/set-key.sh HF_TOKEN
# A Claude or Gemini key is tested with the company first. A key they say is wrong is NOT saved,
# so the key that is already there keeps working.
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
cd "$(dirname "$0")" || exit 1
name="${1:-}"
raw="${2:-}"
if [ -z "$raw" ] && [ -t 0 ] && [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
  printf 'Paste %s now and press Enter (it stays hidden): ' "$name"
  IFS= read -rs raw; echo
fi
value="$(printf '%s' "$raw" | tr -d '[:space:]"'"'"'')"   # drop spaces and quotes that sneak in when pasting
if ! [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || [ -z "$value" ]; then
  echo "Usage: set-key.sh NAME          (then paste the key: it stays hidden)"
  echo "       set-key.sh NAME VALUE    (for example: set-key.sh GOOGLE_TTS_KEY AIzaSy...)"
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
  HF_TOKEN)          code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 https://huggingface.co/api/whoami-v2 -H "Authorization: Bearer $value")" ;;
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
# passwords are never shown; keys only by their last 4 letters; other settings (a model name, on/off) in full
if [[ "$name" =~ (PASSWORD|PASS|SECRET) ]] || { [[ "$name" =~ (KEY|TOKEN) ]] && [ ${#value} -le 12 ]; }; then echo "SAVED $name (${#value} letters)"
elif [[ "$name" =~ (KEY|TOKEN) ]] || [ ${#value} -gt 40 ]; then echo "SAVED $name (ends with ...${value: -4})"
else echo "SAVED $name = $value"; fi
restart
