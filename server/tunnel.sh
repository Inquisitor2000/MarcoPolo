#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR" || exit 1

echo "┌─────────────────────────────────────────────────────────┐"
echo "│  Marco Polo — Tunnel Mode                               │"
echo "│                                                         │"
echo "│  Polo connects via mobile carrier (no WiFi needed).     │"
echo "└─────────────────────────────────────────────────────────┘"
echo ""

# ── 1. Start relay server in background ──
node server.js &
SERVER_PID=$!
sleep 1

# Check if server started
if ! kill -0 $SERVER_PID 2>/dev/null; then
  echo "❌ Server failed to start"
  exit 1
fi
echo "✓ Relay server running on localhost:3000 (PID: $SERVER_PID)"
echo ""

# ── 2. Check / install cloudflared ──
if ! command -v cloudflared &>/dev/null; then
  echo "cloudflared not found. Installing..."
  ARCH=$(uname -m)
  if [[ "$OSTYPE" == "darwin"* ]]; then
    brew install cloudflared 2>/dev/null || {
      echo "❌ brew install failed. Installing manually..."
      curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-amd64.tgz -o /tmp/cloudflared.tgz
      tar -xzf /tmp/cloudflared.tgz -C /tmp
      sudo cp /tmp/cloudflared /usr/local/bin/cloudflared
      sudo chmod +x /usr/local/bin/cloudflared
    }
  elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    CLOUDFLARED_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux"
    if [[ "$ARCH" == "aarch64" ]]; then
      CLOUDFLARED_URL="${CLOUDFLARED_URL}-arm64"
    else
      CLOUDFLARED_URL="${CLOUDFLARED_URL}-amd64"
    fi
    curl -sL "$CLOUDFLARED_URL" -o /tmp/cloudflared
    chmod +x /tmp/cloudflared
    echo "✓ cloudflared installed to /tmp/cloudflared"
    # Provide a way to use it system-wide
    sudo cp /tmp/cloudflared /usr/local/bin/cloudflared 2>/dev/null || true
    CLOUDFLARED="/tmp/cloudflared"
  else
    echo "❌ Unsupported OS. Install cloudflared manually: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"
    kill $SERVER_PID
    exit 1
  fi
  echo "✓ cloudflared installed"
fi

echo ""
echo "┌─────────────────────────────────────────────────────────┐"
echo "│  Starting Cloudflare Tunnel...                          │"
echo "│                                                         │"
echo "│  Polo enters this URL in the app server field:          │"
echo "│                                                         │"

# ── 3. Start cloudflared in background, capture URL from its log ──
TMPLOG=$(mktemp)
CLOUDFLARED="${CLOUDFLARED:-cloudflared}"
$CLOUDFLARED tunnel --url http://localhost:3000 >"$TMPLOG" 2>&1 &
TUNNEL_PID=$!

# Wait for tunnel URL to appear (up to 30s)
PUBLIC_URL=""
for i in $(seq 1 30); do
  sleep 1
  PUBLIC_URL=$(grep -oE 'https://[a-zA-Z0-9.-]+\.trycloudflare\.com' "$TMPLOG" | head -1)
  if [ -n "$PUBLIC_URL" ]; then
    break
  fi
done

rm -f "$TMPLOG"

if [ -z "$PUBLIC_URL" ]; then
  echo "│  ❌ Tunnel failed to establish within 30s               │"
  echo "│     Check cloudflared output above for errors.          │"
  echo "└─────────────────────────────────────────────────────────┘"
  kill $TUNNEL_PID 2>/dev/null
  kill $SERVER_PID 2>/dev/null
  exit 1
fi

echo "│  $PUBLIC_URL  │"
echo "│                                                         │"
echo "│  Works over WiFi, mobile data, anywhere.                │"
echo "└─────────────────────────────────────────────────────────┘"
echo ""
echo "Press Ctrl+C to stop tunnel and server."
echo ""

# Cleanup on exit
trap "kill $TUNNEL_PID 2>/dev/null; kill $SERVER_PID 2>/dev/null; echo 'Stopped.'; exit 0" SIGINT SIGTERM

# Wait for either process to exit
wait $TUNNEL_PID $SERVER_PID 2>/dev/null
