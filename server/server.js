const http = require("http");
const { WebSocketServer } = require("ws");
const crypto = require("crypto");

const PORT = process.env.PORT || 3000;
const ROOM_EXPIRE_MS = 17 * 60 * 1000; // 17min (2min buffer over 15min session)

// ── In-memory rooms ──────────────────────────────────────────────────────────
const rooms = new Map(); // code → { marco: ws|null, polo: ws|null, createdAt }

function generateCode() {
  // 4 alphanumeric chars (0-9, A-Z) — no special characters
  const chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  let code = "";
  const bytes = crypto.randomBytes(4);
  for (let i = 0; i < 4; i++) {
    code += chars[bytes[i] % chars.length];
  }
  return code;
}

function cleanupExpiredRooms() {
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (now - room.createdAt > ROOM_EXPIRE_MS) {
      room.marco?.close(4001, "Session expired");
      room.polo?.close(4001, "Session expired");
      rooms.delete(code);
    }
  }
}
setInterval(cleanupExpiredRooms, 60_000);

// ── HTTP server (REST + WebSocket upgrade) ───────────────────────────────────
const fs = require("fs");
const path = require("path");

const server = http.createServer((req, res) => {
  // CORS
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    res.writeHead(204);
    res.end();
    return;
  }

  // Serve test client
  if (req.method === "GET" && req.url === "/test") {
    const filePath = path.join(__dirname, "test-client.html");
    if (fs.existsSync(filePath)) {
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(fs.readFileSync(filePath));
      return;
    }
  }

  // Deep-link redirect page: serves a clickable https:// URL that redirects to marcopolo://
  const joinMatch = req.url.match(/^\/join\/([A-Za-z0-9]{4})$/);
  if (req.method === "GET" && joinMatch) {
    const code = joinMatch[1];
    const scheme = "marcopolo";
    res.writeHead(200, { "Content-Type": "text/html" });
    res.end(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Opening Marco Polo…</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: system-ui, -apple-system, sans-serif; background: #111; color: #eee; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
  .card { text-align: center; padding: 32px; max-width: 360px; }
  .code { font-size: 48px; font-weight: 800; letter-spacing: 8px; color: #4ade80; margin: 16px 0; font-family: monospace; }
  .btn { display: inline-block; padding: 14px 32px; background: #4ade80; color: #000; font-weight: 600; font-size: 16px; border-radius: 12px; text-decoration: none; margin: 16px 0; border: none; cursor: pointer; }
  .btn:hover { background: #22c55e; }
  .hint { color: #666; font-size: 14px; margin-top: 12px; }
</style>
</head>
<body>
<div class="card">
  <div>🐺 Join Marco Polo</div>
  <div class="code">${code}</div>
  <a class="btn" href="${scheme}://join/${code}">Open Marco Polo</a>
  <p class="hint">Tap the button above to open the app.<br>If nothing happens, type the code into the Polo screen.</p>
</div>
<script>
  // Auto-redirect for browsers that support it
  window.location.href = "${scheme}://join/${code}";
</script>
</body>
</html>`);
    return;
  }

  if (req.method === "POST" && req.url === "/rooms") {
    // Marco creates a room
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      const code = generateCode();
      rooms.set(code, { marco: null, polo: null, createdAt: Date.now() });
      console.log(`[+] Room created: ${code}`);
      res.writeHead(201, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ code, wsUrl: `ws://localhost:${PORT}/ws/${code}` }));
    });
    return;
  }

  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }

  res.writeHead(404);
  res.end("Not found");
});

// ── WebSocket server ─────────────────────────────────────────────────────────
const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const match = url.pathname.match(/^\/ws\/([A-Za-z0-9_-]+)$/);
  if (!match) {
    socket.destroy();
    return;
  }

  const code = match[1];
  const room = rooms.get(code);
  if (!room) {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    // Assign role: first joiner is Marco, second is Polo
    if (!room.marco) {
      room.marco = ws;
      ws.role = "marco";
      console.log(`[+] Marco joined room ${code}`);
    } else if (!room.polo) {
      room.polo = ws;
      ws.role = "polo";
      console.log(`[+] Polo joined room ${code}`);

      // Both connected — notify them
      sendJson(room.marco, { type: "partner_joined", role: "marco" });
      sendJson(room.polo, { type: "partner_joined", role: "polo" });
    } else {
      // Room full
      ws.close(4000, "Room is full");
      return;
    }

    ws.roomCode = code;

    ws.on("message", (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }

      const role = ws.role || "unknown";
      console.log(`[msg] ${role} in room ${code}: type=${msg.type}`);

      // Forward location and route messages to partner
      if (msg.type === "location" || msg.type === "route") {
        const target = ws === room.marco ? room.polo : room.marco;
        if (target && target.readyState === ws.OPEN) {
          msg.from = ws.role;
          sendJson(target, msg);
          console.log(`[msg] forwarded ${msg.type} from ${role} to partner`);
        } else {
          console.log(`[msg] could not forward ${msg.type} — partner WS null or closed`);
        }
      }
    });

    ws.on("close", () => {
      console.log(`[-] ${ws.role || "unknown"} left room ${code}`);
      if (ws === room.marco) room.marco = null;
      if (ws === room.polo) room.polo = null;

      // Notify remaining peer
      const survivor = room.marco || room.polo;
      if (survivor && survivor.readyState === ws.OPEN) {
        sendJson(survivor, { type: "partner_disconnected" });
      }

      // Clean empty rooms
      if (!room.marco && !room.polo) {
        rooms.delete(code);
        console.log(`[-] Room ${code} deleted`);
      }
    });

    ws.on("error", () => {});
  });
});

function sendJson(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

// ── Start ────────────────────────────────────────────────────────────────────
server.listen(PORT, "0.0.0.0", () => {
  // Get LAN IP for the display message
  const { networkInterfaces } = require("os");
  const nets = networkInterfaces();
  let lanIP = "unknown";
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === "IPv4" && !net.internal) {
        lanIP = net.address;
        break;
      }
    }
  }

  console.log(`\n  🏁 Marco Polo Relay Server`);
  console.log(`  ─────────────────────────`);
  console.log(`  Local  : http://localhost:${PORT}`);
  console.log(`  LAN    : http://${lanIP}:${PORT}`);
  console.log(`  WS     : ws://${lanIP}:${PORT}/ws/{code}`);
  console.log(`  Test   : http://localhost:${PORT}/test\n`);
});
