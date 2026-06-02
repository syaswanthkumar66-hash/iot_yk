import { YkpPacket } from '../packet/parser'
import { findTlv, tlvReadFloat, tlvReadUInt32, tlvReadInt8 } from '../packet/parser'
import { insertHealthRecord, logAudit } from '../db/supabase'
import { TlvType } from '../packet/constants'

export interface HealthReportData {
  device_id:      string
  cpu_usage?:     number
  free_heap?:     number
  min_heap?:      number
  rssi?:          number
  temperature?:   number
  battery?:       number
  packet_loss?:   number
  rtt_ms?:        number
  uptime_sec?:    number
  restart_count?: number
  recorded_at:    string
}

export async function handleHealthReport(pkt: YkpPacket): Promise<void> {
  const buf      = pkt.payload
  const deviceId = pkt.header.sourceId

  // Extract TLV fields
  const cpuTlv   = findTlv(buf, TlvType.VALUE_FLOAT)
  const rssiTlv  = findTlv(buf, TlvType.RSSI)

  const cpu       = cpuTlv  ? buf.readFloatBE(cpuTlv.value.byteOffset  + 0) : 0
  const rssi      = rssiTlv ? buf.readInt8(rssiTlv.value.byteOffset)         : 0

  // Parse all floats sequentially from raw buffer for simplicity
  const health = parseHealthPayload(buf, deviceId) as unknown as HealthReportData

  try {
    await insertHealthRecord(deviceId, health as unknown as Record<string, unknown>)
    console.log(`[health] ${deviceId}: heap=${health.free_heap}, rssi=${health.rssi}, cpu=${health.cpu_usage}`)
  } catch (err) {
    console.error('[health] DB insert error:', err)
  }

  // Check alert thresholds
  if (health.free_heap !== undefined && health.free_heap < 50000) {
    console.warn(`[health] ALERT: ${deviceId} low heap ${health.free_heap} bytes`)
    await logAudit(deviceId, 'HEAP_LOW_ALERT', health as unknown as Record<string, unknown>)
  }
  if (health.rssi !== undefined && health.rssi < -85) {
    console.warn(`[health] ALERT: ${deviceId} weak signal ${health.rssi} dBm`)
  }
}

function parseHealthPayload(buf: Buffer, deviceId: string): Record<string, unknown> {
  // Simplified linear TLV parse for health payload
  let i = 0
  let floatIdx  = 0
  let intIdx    = 0
  const floatKeys = ['cpu_usage', 'temperature', 'battery', 'packet_loss', 'rtt_ms']
  const intKeys   = ['free_heap', 'min_heap', 'restart_count']
  const result: Record<string, unknown> = { device_id: deviceId }

  while (i + 3 <= buf.length) {
    const type   = buf[i]
    const length = buf.readUInt16BE(i + 1)
    const valBuf = buf.subarray(i + 3, i + 3 + length)

    if (type === 0x05 && length === 4 && floatIdx < floatKeys.length) {
      result[floatKeys[floatIdx++]] = parseFloat(valBuf.readFloatBE(0).toFixed(2))
    } else if (type === 0x04 && length === 4 && intIdx < intKeys.length) {
      result[intKeys[intIdx++]] = valBuf.readUInt32BE(0)
    } else if (type === 0x12 && length >= 1) {
      result['rssi'] = valBuf.readInt8(0)
    } else if (type === 0x02 && length === 8) {
      result['uptime_sec'] = Number(valBuf.readBigUInt64BE(0))
    }
    i += 3 + length
  }

  result['recorded_at'] = new Date().toISOString()
  return result
}
