#!/usr/bin/env bash
# Installs BigD AI's own brain on this server: Ollama + a free, open AI model (Google Gemma) that fits this server's
# memory. The app then keeps answering even when Claude, Gemini or any other company can't.
# Safe to run again: it only adds what is missing. Run:  true; /root/Big.D/server/brain-setup.sh
# (or press "Install our own brain" on the Teach page).  To choose a model yourself: OWN_BRAIN_MODEL=gemma3:4b in .env
set -u
export PATH=/usr/local/bin:/usr/bin:/bin:$PATH
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA="$SERVER_DIR/data"; mkdir -p "$DATA"
if [ -z "${OWN_BRAIN_MODEL:-}" ] && [ -f "$SERVER_DIR/.env" ]; then
  OWN_BRAIN_MODEL=$(grep -E '^OWN_BRAIN_MODEL=' "$SERVER_DIR/.env" | tail -1 | cut -d= -f2- | tr -d "\"'")
fi

ram=$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)
disk=$(df -Pk "$DATA" | awk 'NR==2 {printf "%d", $4/1024/1024}')
cpus=$(nproc)
echo "This server: ${ram} GB memory, ${cpus} CPUs, ${disk} GB free disk."

# the biggest model that fits (bigger = smarter but slower); each one is tried in turn if a download fails
if [ -n "${OWN_BRAIN_MODEL:-}" ]; then models="$OWN_BRAIN_MODEL"; need=6
elif [ "$ram" -ge 15 ]; then models="gemma4:e4b-it-qat gemma4:e2b gemma3:4b"; need=10
elif [ "$ram" -ge 7 ];  then models="gemma4:e2b-it-qat gemma3:4b gemma3:1b"; need=7
elif [ "$ram" -ge 3 ];  then models="gemma3:1b"; need=3
else echo "NOT INSTALLED: this server has only ${ram} GB memory; the own brain needs at least 4 GB."; exit 1; fi
if [ "$disk" -lt "$need" ]; then echo "NOT INSTALLED: needs about ${need} GB free disk, only ${disk} GB free."; exit 1; fi

# 1. Ollama (runs the model; it listens only inside this server, on 127.0.0.1:11434)
if ! command -v ollama >/dev/null 2>&1; then
  echo "Installing Ollama..."
  curl -fsSL https://ollama.com/install.sh | sh >/dev/null 2>&1 || { echo "NOT INSTALLED: couldn't install Ollama (will try again next time)."; exit 1; }
fi
mkdir -p /etc/systemd/system/ollama.service.d
cat > /etc/systemd/system/ollama.service.d/bigd.conf <<'CONF'
[Service]
Environment="OLLAMA_HOST=127.0.0.1:11434"
Environment="OLLAMA_NUM_PARALLEL=1"
Environment="OLLAMA_MAX_LOADED_MODELS=1"
Nice=10
CONF
systemctl daemon-reload >/dev/null 2>&1; systemctl enable --now ollama >/dev/null 2>&1; systemctl restart ollama >/dev/null 2>&1
for i in $(seq 1 30); do curl -s -o /dev/null http://127.0.0.1:11434/api/tags && break; sleep 1; done
echo "Ollama $(ollama --version 2>/dev/null | grep -o '[0-9][0-9.]*' | head -1) is running."

# 2. the model
got=""
for m in $models; do
  echo "Downloading $m (a few GB, this can take a while)..."
  if ollama pull "$m" 2>&1 | grep -v -E "pulling [0-9a-f]+.*[0-9]+%" | tail -3; ollama list | awk '{print $1}' | grep -qx "$m"; then got="$m"; break; fi
  echo "$m didn't download, trying the next one."
done
[ -z "$got" ] && { echo "NOT INSTALLED: no model could be downloaded (will try again next time)."; exit 1; }
vision=false; ollama show "$got" 2>/dev/null | grep -qi vision && vision=true
printf '{"model":"%s","vision":%s,"installed":"%s","ram":%s,"cpus":%s}\n' "$got" "$vision" "$(date -Iseconds)" "$ram" "$cpus" > "$DATA/ownbrain.json"

# 3. a first answer (this also loads it into memory once)
start=$(date +%s)
ans=$(curl -s http://127.0.0.1:11434/api/generate -d "{\"model\":\"$got\",\"prompt\":\"Say hello in Telugu in one short sentence.\",\"stream\":false,\"keep_alive\":\"5m\"}" | sed -n 's/.*"response":"\([^"]*\)".*/\1/p')
echo "First answer in $(( $(date +%s) - start )) seconds: ${ans:-(none)}"
echo "INSTALLED: our own brain is $got. The app now answers even when Claude can't."
