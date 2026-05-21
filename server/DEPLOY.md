# Deploy Marco Polo Relay to the Cloud

Two options to get a **permanent, fixed URL** for the relay server.

---

## Option A: Railway (recommended — 2 minutes)

1. **Go to** https://railway.com → sign in with Google
2. **Create a new project** → "Deploy from GitHub repo"
3. **Push the `server/` folder to a GitHub repo** (any name, e.g. `marcopolo-relay`)
4. Select that repo in Railway → it auto-detects Node.js → deploys instantly
5. Your relay is now at: `https://<project-name>.up.railway.app`

**No config needed** — the `package.json` and `server.js` are all set.

To verify:
```bash
curl https://<your-url>.up.railway.app/health
# → {"ok":true,"rooms":0}
```

---

## Option B: Render — 2 minutes

1. **Go to** https://render.com → sign in with Google
2. **New Web Service** → connect your GitHub repo with the `server/` folder
3. Use these settings:
   - **Build Command:** `npm ci --production`
   - **Start Command:** `node server.js`
   - **Health Check Path:** `/health`
4. Deploy → done. URL: `https://<name>.onrender.com`

---

## After deploying

Update the `DEFAULT_URL` in `ServerConfig.kt` to your deployed URL:

```kotlin
const val DEFAULT_URL = "https://your-project.up.railway.app"
```

Or just type it into the app's **Relay Server** field on the Home screen — no rebuild needed.

---

## Tunnel (quick test — URL changes on restart)

Already running now at:
```
https://respond-inner-extent-noted.trycloudflare.com
```

Run `bash start-tunnel.sh` to get a fresh tunnel URL if it stops.
