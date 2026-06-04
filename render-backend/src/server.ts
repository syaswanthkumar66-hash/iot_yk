import 'dotenv/config'
import express from 'express'
import cors from 'cors'
import helmet from 'helmet'
import http from 'http'
import { WebSocketServer } from 'ws'
import path from 'path'
import fs from 'fs'
import { startYkpRouter } from './router/ykp-router'
import devicesApi from './api/devices'
import healthApi  from './api/health'
import otaApi     from './api/ota'
import authApi    from './api/auth'

const PORT = parseInt(process.env.PORT ?? '8080')
const app  = express()

// ── Middleware ────────────────────────────
app.use(helmet({
  contentSecurityPolicy: false,
  crossOriginResourcePolicy: { policy: "cross-origin" },
  crossOriginEmbedderPolicy: false
}))
app.use(cors({ origin: '*' }))
app.use(express.json({ limit: '10mb' }))

// ── Health check (Render uptime monitor) ──
app.get('/health', (_req, res) => {
  const hasSupabaseUrl = !!process.env.SUPABASE_URL
  const hasSupabaseKey = !!process.env.SUPABASE_SERVICE_KEY
  const configured = hasSupabaseUrl && hasSupabaseKey

  res.json({
    status:    configured ? 'ok' : 'warning',
    service:   'ykp-router',
    version:   'v5',
    timestamp: new Date().toISOString(),
    database:  configured ? 'connected' : 'error: SUPABASE_URL or SUPABASE_SERVICE_KEY is missing on Render settings!',
    instructions: configured ? undefined : 'Please go to your Render Dashboard -> ykp-router -> Environment, and add SUPABASE_URL and SUPABASE_SERVICE_KEY.'
  })
})

// ── Public SSL Certificate Endpoints (used by BLE provisioning client) ──
app.get('/api/ssl-cert', (_req, res) => {
  const cert = process.env.SSL_CERT
  if (!cert) {
    return res.status(404).send('SSL_CERT environment variable is not configured on this server.')
  }
  res.type('text/plain').send(cert)
})

app.get('/api/cert', (_req, res) => {
  const cert = process.env.SSL_CERT
  if (!cert) {
    return res.status(404).send('SSL_CERT environment variable is not configured on this server.')
  }
  res.type('text/plain').send(cert)
})

// ── Serve Static Web Dashboard if built ────
const distPath = path.join(__dirname, '../../web-dashboard/dist')
if (fs.existsSync(distPath)) {
  console.log(`[ykp-router] Serving static web dashboard from: ${distPath}`)
  app.use(express.static(distPath))
} else {
  console.warn(`[ykp-router] Static web dashboard not found at: ${distPath}. Running in API-only mode.`)
}

// ── REST API routes ────────────────────────
app.use('/api/devices', devicesApi)
app.use('/api/health',  healthApi)
app.use('/api/ota',     otaApi)
app.use('/api/auth',    authApi)

// ── SPA routing fallback ──
app.get('*', (req, res, next) => {
  // If it's an API, WebSocket, or health check route, don't fallback to index.html
  if (req.path.startsWith('/api') || req.path.startsWith('/ws') || req.path === '/health') {
    return next()
  }
  
  const indexPath = path.join(distPath, 'index.html')
  if (fs.existsSync(indexPath)) {
    res.sendFile(indexPath)
  } else {
    next()
  }
})

// ── 404 fallback ───────────────────────────
app.use((_req, res) => {
  res.status(404).json({ error: 'Not found' })
})

// ── HTTP + WebSocket server ────────────────
const server = http.createServer(app)
const wss    = new WebSocketServer({ server, path: '/ws' })

startYkpRouter(wss)

server.listen(PORT, () => {
  console.log('══════════════════════════════════════')
  console.log(`  YKP Router v5 listening on :${PORT}`)
  console.log(`  WS  → ws://127.0.0.1:${PORT}/ws`)
  console.log(`  API → http://127.0.0.1:${PORT}/api`)
  console.log('══════════════════════════════════════')
})

export default app
