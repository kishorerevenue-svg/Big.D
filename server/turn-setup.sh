#!/bin/bash
# Habit AI: sets up the call relay (coturn) for Habit voice and video calls. Run it once on the server:
#     true; /root/Big.D/server/turn-setup.sh
# Calls go straight between the two phones when they can. On mobile data (Jio, Airtel…) phones often can't reach each
# other directly, so this relay passes the call on. The call stays locked (DTLS-SRTP): the relay can't hear or see it.
# It only works with short-lived passwords that the Habit server gives to logged-in Habit Chat users.
set -e
cd "$(dirname "$0")"
ENV=.env
touch "$ENV"

SECRET=$(grep -E '^TURN_SECRET=' "$ENV" | tail -1 | cut -d= -f2-)
if [ -z "$SECRET" ]; then
  SECRET=$(openssl rand -hex 24)
  echo "TURN_SECRET=$SECRET" >> "$ENV"
fi
HOST=$(grep -E '^TURN_HOST=' "$ENV" | tail -1 | cut -d= -f2-)
[ -z "$HOST" ] && HOST=bigdai.tech
IP=$(curl -s4 --max-time 10 https://api.ipify.org || true)
[ -z "$IP" ] && IP=$(hostname -I | awk '{print $1}')

echo "▶ Installing the call relay (coturn)…"
DEBIAN_FRONTEND=noninteractive apt-get install -y coturn >/dev/null

cat > /etc/turnserver.conf <<EOF
# Habit AI call relay (made by turn-setup.sh)
listening-port=3478
fingerprint
use-auth-secret
static-auth-secret=$SECRET
realm=$HOST
external-ip=$IP
min-port=49160
max-port=49260
no-cli
no-tls
no-dtls
no-multicast-peers
no-tcp-relay
# never relay into this server's own or private networks
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.0.0.0-192.0.0.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=198.18.0.0-198.19.255.255
denied-peer-ip=::1
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
user-quota=12
total-quota=120
max-bps=1500000
stale-nonce=600
simple-log
log-file=/var/log/turnserver.log
EOF
[ -f /etc/default/coturn ] && sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
systemctl enable coturn >/dev/null 2>&1 || true
systemctl restart coturn

# open the doors for it, if this server's firewall (ufw) is on
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow 3478/udp >/dev/null; ufw allow 3478/tcp >/dev/null; ufw allow 49160:49260/udp >/dev/null
  echo "▶ Firewall: opened 3478 and 49160-49260 (UDP) for calls."
fi

# the Habit server reads TURN_SECRET when it starts
if command -v pm2 >/dev/null 2>&1; then pm2 restart soul-friend --update-env >/dev/null && pm2 save >/dev/null || true; fi

if systemctl is-active --quiet coturn; then
  echo "✅ The call relay is on. Habit calls now work on mobile data too."
  echo "   (If calls still don't connect on mobile data: in Hostinger's VPS panel › Firewall, allow UDP 3478 and 49160-49260.)"
else
  echo "⚠️  The call relay didn't start. Send a screenshot of: systemctl status coturn"
fi
