#!/bin/bash
# Start the relay server + cloudflare tunnel with URL auto-capture
# Prints the public URL so you can update the app.

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR" || exit 1

echo "┌─────────────────────────────────────────────────────────┐"
echo "│  Starting Marco Polo Relay + Tunnel                     │"
echo "└─────────────────────────────────────────────────────────┘"

# ── 1. Kill any existing server on port 3000 ──
kill $(lsof -t -i:3000) 2>/dev/null
sleep 0.5

# ── 2. Start relay server ──
node server.js &
SERVER_PID=$!
sleep 1
if ! kill -0 $SERVER_PID 2>/dev/null; then
  echo "❌ Server failed to start"
  exit 1
fi
echo "✓ Relay server running (PID: $SERVER_PID)"

# ── 3. Find/install cloudflared ──
CLOUDFLARED=""
for cmd in cloudflared /tmp/cloudflared; do
  if command -v $cmd &>/dev/null; then
    CLOUDFLARED=$cmd
    break
  fi
done

if [ -z "$CLOUDFLARED" ]; then
  echo "Installing cloudflared..."
  ARCH=$(uname -m)
  case "$ARCH" in
    aarch64)  CF_ARCH="arm64" ;;
    armv7l)   CF_ARCH="arm" ;;
    *)        CF_ARCH="amd64" ;;
  esac
  curl -sL "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}" -o /tmp/cloudflared
  chmod +x /tmp/cloudflared
  CLOUDFLARED=/tmp/cloudflared
fi

echo "✓ Using cloudflared: $($CLOUDFLARED --version 2>&1 | head -1)"

# ── 4. Start tunnel and capture URL ──
TMPLOG=$(mktemp)
$CLOUDFLARED tunnel --url http://localhost:3000 >"$TMPLOG" 2>&1 &
TUNNEL_PID=$!

PUBLIC_URL=""
for i in $(seq 1 30); do
  sleep 1
  PUBLIC_URL=$(grep -oE 'https://[a-zA-Z0-9.-]+\.trycloudflare\.com' "$TMPLOG" | head -1)
  if [ -n "$PUBLIC_URL" ]; then
    break
  fi
done

echo ""
if [ -n "$PUBLIC_URL" ]; then
  echo "┌─────────────────────────────────────────────────────────┐"
  echo "│  🌍 Public URL:                                        │"
  echo "│  $PUBLIC_URL  │"
  echo "│                                                         │"
  echo "│  Set this as the Relay Server URL in the app.           │"
  echo "│  Works over cellular and WiFi.                          │"
  echo "└─────────────────────────────────────────────────────────┘"

  # Also save to a file for reference
  echo "$PUBLIC_URL" > /tmp/marcopolo-public-url.txt
else
  echo "⚠️  Could not detect tunnel URL. Check the log:"
fi

echo ""
echo "Press Ctrl+C to stop tunnel + server."
echo ""

# Cleanup
trap "kill $TUNNEL_PID 2>/dev/null; kill $SERVER_PID 2>/dev/null; rm -f $TMPLOG; echo 'Stopped.'" SIGINT SIGTERM
wait $TUNNEL_PID $SERVER_PID 2>/dev/null
