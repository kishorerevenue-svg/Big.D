#!/usr/bin/env bash
# Puts Habit's own trained model, habit-gemma, on this server as our own brain. It is made for free on Kaggle with
# brain/habit-gemma.ipynb and kept in your private Hugging Face repo (<your Hugging Face name>/habit-gemma).
# First save your Hugging Face token (it stays in .env on this server):
#   bash /root/Big.D/server/set-key.sh HF_TOKEN hf_...
# Then:                       true; /root/Big.D/server/brain-model.sh         (or the button on the Teach page)
# Back to the plain Gemma:    true; /root/Big.D/server/brain-model.sh --back
# Safe to run again: a stopped download continues where it stopped, and nothing changes unless habit-gemma answers.
set -u
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA="$SERVER_DIR/data"; mkdir -p "$DATA"
CONF="$DATA/ownbrain.json"
HF="${HF_BASE:-https://huggingface.co}"
OLLAMA_URL="${OWN_BRAIN_URL:-http://127.0.0.1:11434}"
NAME=habit-gemma

env_get() { [ -f "$SERVER_DIR/.env" ] && grep -E "^$1=" "$SERVER_DIR/.env" | tail -1 | cut -d= -f2- | tr -d "\"'"; }
TOKEN="${HF_TOKEN:-$(env_get HF_TOKEN)}"
REPO="${HABIT_MODEL_REPO:-$(env_get HABIT_MODEL_REPO)}"
# a value from a JSON text (stdin) at a path like gguf.file
jget() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{let v=JSON.parse(s);for(const k of process.argv[1].split("."))v=v==null?v:v[k];process.stdout.write(v==null?"":String(v))}catch(e){}})' "$1"; }
conf_get() { cat "$CONF" 2>/dev/null | jget "$1"; }
conf_set() { # model, base (or ""), vision true/false
  node -e 'const fs=require("fs"),f=process.argv[1];let c={};try{c=JSON.parse(fs.readFileSync(f,"utf8"))}catch(e){}
c.model=process.argv[2];if(process.argv[3])c.base=process.argv[3];else delete c.base;c.vision=process.argv[4]==="true";c.changed=new Date().toISOString();
fs.writeFileSync(f,JSON.stringify(c)+"\n")' "$CONF" "$1" "$2" "$3"
}
# OWN_BRAIN_MODEL in .env wins over ownbrain.json: when it is there, change it too (and restart the app server)
env_model() {
  if grep -qE '^OWN_BRAIN_MODEL=' "$SERVER_DIR/.env" 2>/dev/null; then
    { grep -v '^OWN_BRAIN_MODEL=' "$SERVER_DIR/.env"; printf 'OWN_BRAIN_MODEL=%s\n' "$1"; } > "$SERVER_DIR/.env.new" && mv "$SERVER_DIR/.env.new" "$SERVER_DIR/.env" && chmod 600 "$SERVER_DIR/.env"
    pm2 restart soul-friend --update-env >/dev/null 2>&1 && echo "server restarted"
  fi
}
has_model() { ollama list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qE "^$1(:latest)?\$"; }
first_answer() {
  curl -s --max-time 300 "$OLLAMA_URL/api/generate" -d "{\"model\":\"$1\",\"prompt\":\"Say hello in Telugu in one short sentence.\",\"stream\":false,\"keep_alive\":\"5m\"}" | jget response | head -c 300
}
vision_of() { ollama show "$1" 2>/dev/null | grep -qi vision && echo true || echo false; }

if [ "${1:-}" = "--back" ]; then
  base="$(conf_get base)"
  [ -z "$base" ] && { echo "NOTHING TO DO: there is no earlier model to go back to."; exit 1; }
  has_model "$base" || { echo "NOT DONE: $base is not on this server any more. Press 'Install our own brain' on the Teach page."; exit 1; }
  conf_set "$base" "" "$(vision_of "$base")"
  env_model "$base"
  echo "DONE: our own brain is $base again. habit-gemma stays on this server: press 'Put habit-gemma on this server' to use it again."
  exit 0
fi

[ -z "$TOKEN" ] && { echo "NOT DONE: save your Hugging Face token first:  bash $SERVER_DIR/set-key.sh HF_TOKEN hf_..."; exit 1; }
command -v ollama >/dev/null 2>&1 || { echo "NOT DONE: our own brain (Ollama) isn't installed yet. Press 'Install our own brain' on the Teach page first."; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
if [ -z "$REPO" ]; then
  user="$(curl -fsS --max-time 20 -H "$AUTH" "$HF/api/whoami-v2" | jget name)"
  [ -z "$user" ] && { echo "NOT DONE: Hugging Face didn't accept the token. Make a new token (type: Write) and save it again."; exit 1; }
  REPO="$user/habit-gemma"
fi
echo "Looking for habit-gemma in $REPO (private)..."
report="$(curl -fsSL --max-time 30 -H "$AUTH" "$HF/$REPO/resolve/main/report.json" 2>/dev/null)" || { echo "NOT DONE: there is no habit-gemma in $REPO yet. Run the Kaggle notebook first (brain/README.md)."; exit 1; }
gguf="$(printf '%s' "$report" | jget gguf.file)"
bytes="$(printf '%s' "$report" | jget gguf.bytes)"
[ -z "$gguf" ] && { echo "NOT DONE: report.json in $REPO doesn't name the model file. Run the Kaggle notebook again."; exit 1; }
mb=$(( ${bytes:-0} / 1048576 ))
echo "Found: made $(printf '%s' "$report" | jget made | cut -c1-10), from Gemma $(printf '%s' "$report" | jget size), exam: habit-gemma $(printf '%s' "$report" | jget exam.habit.score)% (plain Gemma $(printf '%s' "$report" | jget exam.base.score)%), file $gguf (${mb} MB)."

# 1. room for it: memory to run it, and disk for the download and Ollama's copy
ram=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
need=$(( mb + 1500 ))
if [ "$mb" -gt 0 ] && [ "$ram" -lt "$need" ]; then
  echo "NOT DONE: habit-gemma needs about $(( (need + 1023) / 1024 )) GB memory, and this server has $(( ram / 1024 )) GB. It will work on a bigger server (or train the smaller E2B size)."; exit 1
fi
dir="$DATA/habit-gemma"; mkdir -p "$dir"
part="$dir/habit-gemma.gguf.part"
have=$(stat -c %s "$part" 2>/dev/null || echo 0)
free=$(df -Pm "$DATA" | awk 'NR==2 {print $4}')
if [ "$mb" -gt 0 ] && [ "$free" -lt $(( mb * 2 + 500 - have / 1048576 )) ]; then
  echo "NOT DONE: needs about $(( mb * 2 / 1024 + 1 )) GB free disk, only $(( free / 1024 )) GB free."; exit 1
fi

# 2. the download (a stopped one continues)
if [ -n "$bytes" ] && [ "$have" = "$bytes" ]; then echo "Already downloaded."
else
  echo "Downloading ${mb} MB (this can take a while)..."
  curl -fL --retry 5 --retry-delay 10 -C - -sS -H "$AUTH" -o "$part" "$HF/$REPO/resolve/main/$gguf" || { echo "NOT DONE: the download stopped. Run this again: it continues where it stopped."; exit 1; }
fi
got=$(stat -c %s "$part" 2>/dev/null || echo 0)
if [ -n "$bytes" ] && [ "$got" != "$bytes" ]; then echo "NOT DONE: the file is $got bytes, it should be $bytes. Run this again."; exit 1; fi
mv -f "$part" "$dir/habit-gemma.gguf"
curl -fsSL --max-time 30 -H "$AUTH" -o "$dir/Modelfile" "$HF/$REPO/resolve/main/Modelfile" || printf 'FROM ./habit-gemma.gguf\n' > "$dir/Modelfile"
grep -qE '^FROM ' "$dir/Modelfile" || { echo "NOT DONE: the Modelfile in $REPO is broken. Run the Kaggle notebook again."; exit 1; }
printf '%s' "$report" > "$dir/report.json"

# 3. into Ollama under a new name, and a first answer, before anything changes
echo "Adding it to Ollama..."
NEW="$NAME-new"
ollama rm "$NEW" >/dev/null 2>&1
(cd "$dir" && ollama create "$NEW" -f Modelfile 2>&1 | grep -v -E "^(gathering|copying|parsing|using|creating|writing|transferring)" | tail -3)
has_model "$NEW" || { echo "NOT DONE: Ollama couldn't add habit-gemma (see above). Our own brain stays as it was."; exit 1; }
start=$(date +%s)
ans="$(first_answer "$NEW")"
[ -z "$ans" ] && { ollama rm "$NEW" >/dev/null 2>&1; echo "NOT DONE: habit-gemma didn't answer. Our own brain stays as it was."; exit 1; }
echo "First answer in $(( $(date +%s) - start )) seconds: $ans"
ollama cp "$NEW" "$NAME" >/dev/null && ollama rm "$NEW" >/dev/null 2>&1
has_model "$NAME" || { echo "NOT DONE: Ollama couldn't name it habit-gemma. Our own brain stays as it was."; exit 1; }
rm -f "$dir/habit-gemma.gguf"   # Ollama keeps its own copy

# 4. habit-gemma becomes our own brain (the model before it is kept, for --back)
cur="$(conf_get model)"; base="$(conf_get base)"
[ "$cur" != "$NAME" ] && [ -n "$cur" ] && base="$cur"
conf_set "$NAME" "$base" false
env_model "$NAME"
echo "DONE: our own brain is now habit-gemma, Habit's own trained model.${base:+ To go back: brain-model.sh --back (to $base).}"
