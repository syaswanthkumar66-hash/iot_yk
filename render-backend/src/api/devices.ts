import { Router, Request, Response } from 'express'
import { supabase } from '../db/supabase'
import { deviceConnections } from '../router/route-engine'
import { buildPacket } from '../packet/builder'
import { RouteType, ServiceId, RelayAction, QoS } from '../packet/constants'
import { sendToDevice } from '../router/route-engine'
import { authMiddleware } from '../middleware/auth'

const router = Router()
router.use(authMiddleware)

/** GET /api/devices — list all devices */
router.get('/', async (_req: Request, res: Response) => {
  try {
    const { data, error } = await supabase
      .from('devices').select('*, device_state(relay_state, sensor_data)').order('device_id')
    if (error) return res.status(500).json({ error: error.message })
    if (!data) return res.json([])
    // Annotate with real-time online status from WS connections
    const devices = data.map(d => ({
      ...d,
      is_online: deviceConnections.has(d.device_id),
      relay_state: d.device_state?.relay_state || false,
      rssi: d.device_state?.sensor_data?.rssi,
      free_heap: d.device_state?.sensor_data?.free_heap
    }))
    return res.json(devices)
  } catch (err: any) {
    console.error('[devices api] error:', err.message || err)
    return res.status(500).json({ error: 'Database connection failed' })
  }
})

/** GET /api/devices/:id */
router.get('/:id', async (req: Request, res: Response) => {
  try {
    const { data, error } = await supabase
      .from('devices').select('*, device_state(relay_state, sensor_data)').eq('device_id', req.params.id).single()
    if (error || !data) return res.status(404).json({ error: 'Not found' })
    return res.json({ 
      ...data, 
      is_online: deviceConnections.has(data.device_id),
      relay_state: data.device_state?.relay_state || false,
      rssi: data.device_state?.sensor_data?.rssi,
      free_heap: data.device_state?.sensor_data?.free_heap
    })
  } catch (err: any) {
    console.error('[devices api] error:', err.message || err)
    return res.status(500).json({ error: 'Database connection failed' })
  }
})

/** POST /api/devices/:id/command — send command to device */
router.post('/:id/command', async (req: Request, res: Response) => {
  const deviceId = req.params.id
  const { service, action } = req.body as { service: number; action: number }

  if (!deviceConnections.has(deviceId)) {
    return res.status(503).json({ error: 'Device offline' })
  }

  const pkt = buildPacket({
    packetId:  Math.floor(Math.random() * 0xFFFFFF),
    sessionId: 0,
    sourceId:  'SERVER',
    destId:    deviceId,
    routeType: RouteType.DIRECT,
    serviceId: service,
    actionId:  action,
    qos:       QoS.QOS_2,
  })

  const sent = sendToDevice(deviceId, pkt)
  return res.json({ success: sent, deviceId, service, action })
})

export default router
