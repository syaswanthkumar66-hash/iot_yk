import 'dotenv/config'
import express from 'express'
import cors from 'cors'
import helmet from 'helmet'
import http from 'http'
import { WebSocketServer } from 'ws'
import { startYkpRouter } from './router/ykp-router'
import devicesApi from './api/devices'
import healthApi  from './api/health'
import otaApi     from './api/ota'

const PORT = parseInt(process.env.PORT ?? '8080')
const app  = express()

// ── Middleware ────────────────────────────
app.use(helmet({ contentSecurityPolicy: false }))
app.use(cors({ origin: '*' }))
app.use(express.json({ limit: '10mb' }))

// ── Health check (Render uptime monitor) ──
app.get('/health', (_req, res) => {
  res.json({
    status:    'ok',
    service:   'ykp-router',
    version:   'v5',
    timestamp: new Date().toISOString(),
  })
})

// ── REST API routes ────────────────────────
app.use('/api/devices', devicesApi)
app.use('/api/health',  healthApi)
app.use('/api/ota',     otaApi)

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
  console.log(`  WS  → ws://localhost:${PORT}/ws`)
  console.log(`  API → http://localhost:${PORT}/api`)
  console.log('══════════════════════════════════════')
})

export default app
