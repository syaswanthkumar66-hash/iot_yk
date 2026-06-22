import 'dotenv/config'
import express from 'express'
import cors from 'cors'
import helmet from 'helmet'
import rateLimit from 'express-rate-limit'
import http from 'http'
import { WebSocketServer } from 'ws'
import path from 'path'
import fs from 'fs'
import { startYkpRouter } from './router/ykp-router'
import devicesApi from './api/devices'
import healthApi  from './api/health'
import otaApi     from './api/ota'
import authApi    from './api/auth'
import streamApi  from './api/stream'

const PORT = parseInt(process.env.PORT ?? '8080')
const app  = express()

// ── Middleware ────────────────────────────
app.use(helmet({
  contentSecurityPolicy: false,
  crossOriginResourcePolicy: { policy: "cross-origin" },
  crossOriginEmbedderPolicy: false
}))

/* S6 fix: restrict CORS to known frontend origin(s) only.
   Set FRONTEND_ORIGIN env var to your deployed frontend URL.
   Falls back to localhost:3000 for development. */
const ALLOWED_ORIGINS = (process.env.FRONTEND_ORIGIN ?? 'http://localhost:3000').split(',')
app.use(cors({
  origin: (origin, cb) => {
    // Allow no-origin (curl/Postman/same-host) or known origins
    if (!origin || ALLOWED_ORIGINS.includes(origin)) return cb(null, true)
    cb(new Error(`CORS blocked: ${origin}`))
  },
  credentials: true,
}))
app.use(express.json({ limit: '10mb' }))

/* S6 fix: rate limit sensitive REST endpoints */
const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 30, standardHeaders: true, legacyHeaders: false })
const otaLimiter  = rateLimit({ windowMs: 60 * 60 * 1000, max: 10, standardHeaders: true, legacyHeaders: false })

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

// ── Server running in API-only mode ────

// ── REST API routes ────────────────────────
app.use('/api/devices', devicesApi)
app.use('/api/health',  healthApi)
app.use('/api/auth',    authLimiter, authApi)   /* S6: rate limited */
app.use('/api/ota',     otaLimiter,  otaApi)    /* S6: rate limited */
app.use('/api/stream',  streamApi)

// (No SPA fallback required — frontend runs independently)

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
