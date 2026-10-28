#!/usr/bin/env bash
# One-time setup for an Ubuntu 22.04 / 24.04 VPS (for example Hostinger KVM 1).
# Run as root from inside the server folder:   bash setup.sh yourdomain.com
set -e
DOMAIN="$1"
if [ -z "$DOMAIN" ]; then echo "Usage: bash setup.sh yourdomain.com"; exit 1; fi
APP_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "== 1/5 Installing Node.js 22 =="
apt-get update -y
apt-get install -y curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y nodejs

echo "== 2/5 Installing PM2 (keeps the server running) =="
npm install -g pm2

echo "== 3/5 Installing Caddy (free automatic HTTPS) =="
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
apt-get update -y && apt-get install -y caddy
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    reverse_proxy localhost:3000
}
EOF
systemctl reload caddy

echo "== 4/5 Preparing settings =="
cd "$APP_DIR"
npm install --omit=dev --no-audit --no-fund
[ -f .env ] || cp .env.example .env
echo "Now open .env and paste your API key:   nano $APP_DIR/.env"

echo "== 5/5 Starting BigD AI server =="
pm2 start server.js --name soul-friend
pm2 save
pm2 startup systemd -u root --hp /root | tail -1 | bash || true

ufw allow 22 >/dev/null 2>&1 || true
ufw allow 80 >/dev/null 2>&1 || true
ufw allow 443 >/dev/null 2>&1 || true

echo ""
echo "Done. After adding your API key run:  pm2 restart soul-friend"
echo "Then open https://$DOMAIN/health in your phone browser. It should say keySet: true"
