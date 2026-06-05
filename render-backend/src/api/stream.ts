import { Router, Request, Response } from 'express'
import { appEvents } from '../events'
import { authMiddleware } from '../middleware/auth'
import { supabase } from '../db/supabase'

const router = Router()
router.use(authMiddleware)

/** GET /api/stream — Server-Sent Events for Live Sync */
router.get('/', async (req: Request, res: Response) => {
  const user = (req as any).user
  if (!user) {
    return res.status(401).end()
  }

  // Fetch user's devices to filter events
  const { data } = await supabase.from('devices').select('device_id').eq('user_id', user.id)
  const userDevices = new Set((data || []).map(d => d.device_id))

  // Set headers for SSE
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
  })

  // Send an initial connected event
  res.write(`data: ${JSON.stringify({ type: 'CONNECTED', timestamp: Date.now() })}\n\n`)

  // Define listeners
  const onStateChanged = (data: { deviceId: string, relay_state: boolean }) => {
    if (userDevices.has(data.deviceId)) {
      res.write(`data: ${JSON.stringify({ type: 'DEVICE_STATE_CHANGED', ...data })}\n\n`)
    }
  }

  const onPresenceChanged = (data: { deviceId: string, is_online: boolean }) => {
    if (userDevices.has(data.deviceId)) {
      res.write(`data: ${JSON.stringify({ type: 'DEVICE_PRESENCE_CHANGED', ...data })}\n\n`)
    }
  }

  appEvents.on('device_state_changed', onStateChanged)
  appEvents.on('device_presence_changed', onPresenceChanged)

  // Send heartbeat ping every 25 seconds to keep the connection alive
  const heartbeat = setInterval(() => {
    res.write(`data: ${JSON.stringify({ type: 'PING' })}\n\n`)
  }, 25000)

  // Cleanup on client disconnect
  req.on('close', () => {
    clearInterval(heartbeat)
    appEvents.off('device_state_changed', onStateChanged)
    appEvents.off('device_presence_changed', onPresenceChanged)
  })
})

export default router
