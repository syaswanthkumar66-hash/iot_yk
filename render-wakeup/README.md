# Render Backend Wakeup Utility

This simple utility keeps your Render-hosted Node.js Express backend awake by sending a ping request to its public `/health` endpoint periodically. This prevents Render from spinning down the free-tier backend web service due to 15 minutes of inactivity.

## How to Deploy on Render

1. Log into your [Render Dashboard](https://dashboard.render.com).
2. Click **New +** and select **Cron Job**.
3. Connect your repository containing this code.
4. Configure the following deployment settings:

| Setting | Value |
|---------|-------|
| **Name** | `ykp-backend-wakeup` |
| **Environment** | `Node` |
| **Region** | Select same region as backend service |
| **Branch** | `main` |
| **Root Directory** | `render-wakeup` |
| **Build Command** | *Leave empty* (or `npm install`) |
| **Start Command** | `node wakeup.js` |
| **Schedule** | `*/10 * * * *` *(Runs every 10 minutes)* |

5. Under **Environment Variables**, add:

| Key | Value | Description |
|-----|-------|-------------|
| **BACKEND_URL** | `https://your-backend-app.onrender.com` | The base URL of your live Render backend web service. |

6. Click **Create Cron Job**.
