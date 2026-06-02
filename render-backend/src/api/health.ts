import { Router, Request, Response } from 'express'
import { supabase } from '../db/supabase'

const router = Router()

/** GET /api/health/:deviceId — latest snapshot */
router.get('/:deviceId', async (req: Request, res: Response) => {
  const { data, error } = await supabase
    .from('device_health')
    .select('*')
    .eq('device_id', req.params.deviceId)
    .order('recorded_at', { ascending: false })
    .limit(1)
    .maybeSingle()
  if (error || !data) return res.status(404).json({ error: 'No health data' })
  return res.json(data)
})

/** GET /api/health/:deviceId/history?hours=24 */
router.get('/:deviceId/history', async (req: Request, res: Response) => {
  const hours = parseInt(String(req.query.hours ?? '24'))
  const since = new Date(Date.now() - hours * 3_600_000).toISOString()
  const { data, error } = await supabase
    .from('device_health')
    .select('cpu_usage,free_heap,rssi,rtt_ms,recorded_at')
    .eq('device_id', req.params.deviceId)
    .gte('recorded_at', since)
    .order('recorded_at', { ascending: true })
  if (error) return res.status(500).json({ error: error.message })
  return res.json(data)
})

export default router
