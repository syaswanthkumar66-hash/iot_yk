import { Router, Request, Response } from 'express'
import { supabase } from '../db/supabase'
import { sendToDevice } from '../router/route-engine'
import { buildPacket, TlvBuilder } from '../packet/builder'
import { RouteType, ServiceId, OtaAction, QoS, TlvType } from '../packet/constants'
import { v4 as uuidv4 } from 'uuid'

const router = Router()

/** GET /api/ota — list all OTA jobs */
router.get('/', async (_req: Request, res: Response) => {
  try {
    const { data, error } = await supabase
      .from('ota_jobs').select('*').order('created_at', { ascending: false })
    if (error) return res.status(500).json({ error: error.message })
    return res.json(data)
  } catch (err: any) {
    console.error('[ota api] error:', err.message || err)
    return res.status(500).json({ error: 'Database connection failed' })
  }
})

/** POST /api/ota — create OTA job */
router.post('/', async (req: Request, res: Response) => {
  try {
    const { device_id, firmware_url, firmware_ver, firmware_sha256,
            firmware_sig, firmware_size } = req.body

    const jobId = uuidv4()
    const chunkSize   = 4096
    const chunksTotal = Math.ceil(firmware_size / chunkSize)

    const { error } = await supabase.from('ota_jobs').insert({
      job_id:          jobId,
      device_id,
      firmware_url,
      firmware_ver,
      firmware_sha256,
      firmware_sig,
      firmware_size,
      chunk_size:      chunkSize,
      chunks_total:    chunksTotal,
      status:          'pending',
      created_at:      new Date().toISOString(),
    })

    if (error) return res.status(500).json({ error: error.message })
    return res.status(201).json({ job_id: jobId, chunks_total: chunksTotal })
  } catch (err: any) {
    console.error('[ota api] error:', err.message || err)
    return res.status(500).json({ error: 'Database connection failed' })
  }
})

/** POST /api/ota/:jobId/start — send OTA_BEGIN to device */
router.post('/:jobId/start', async (req: Request, res: Response) => {
  try {
    const { data: job, error } = await supabase
      .from('ota_jobs').select('*').eq('job_id', req.params.jobId).maybeSingle()
    if (error || !job) return res.status(404).json({ error: 'Job not found' })

    const payload = new TlvBuilder()
      .addString(TlvType.FIRMWARE_VER, job.firmware_ver)
      .addUInt32(TlvType.VALUE_INT,    job.firmware_size)
      .addBytes(TlvType.HASH,          Buffer.from(job.firmware_sha256, 'hex'))
      .addBytes(TlvType.SIGNATURE,     Buffer.from(job.firmware_sig,    'hex'))
      .addUInt32(TlvType.VALUE_INT,    job.chunk_size)
      .addUInt32(TlvType.VALUE_INT,    job.chunks_total)
      .build()

    const pkt = buildPacket({
      packetId:  Math.floor(Math.random() * 0xFFFFFF),
      sessionId: 0,
      sourceId:  'SERVER',
      destId:    job.device_id,
      routeType: RouteType.DIRECT,
      serviceId: ServiceId.OTA,
      actionId:  OtaAction.BEGIN,
      qos:       QoS.QOS_2,
      payload,
    })

    const sent = sendToDevice(job.device_id, pkt)
    if (!sent) return res.status(503).json({ error: 'Device offline' })

    await supabase.from('ota_jobs')
      .update({ status: 'in_progress', started_at: new Date().toISOString() })
      .eq('job_id', req.params.jobId)

    return res.json({ success: true, job_id: job.job_id })
  } catch (err: any) {
    console.error('[ota api] error:', err.message || err)
    return res.status(500).json({ error: 'Database connection failed' })
  }
})

export default router
