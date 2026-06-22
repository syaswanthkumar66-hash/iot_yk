import { YkpPacket } from '../packet/parser'
import { parseTlv, tlvReadFloat, tlvReadUInt32, tlvReadInt8, tlvReadString } from '../packet/parser'
import { insertHealthRecord, logAudit, upsertDeviceState } from '../db/supabase'
import { TlvType } from '../packet/constants'

export interface HealthReportData {
  device_id:      string
  cpu_usage?:     number
  free_heap?:     number
  min_heap?:      number
  rssi?:          number
  temperature?:   number
  packet_loss?:   number
  rtt_ms?:        number
  uptime_sec?:    number
  restart_count?: number
  recorded_at:    string
}

export async function handleHealthReport(pkt: YkpPacket): Promise<void> {
  const buf      = pkt.payload
  const deviceId = pkt.header.sourceId

  /* S1 fix: use CBOR parser (parseTlv) — firmware sends CBOR indefinite maps.
     The old code read raw TLV bytes which broke on every packet. */
  const health = parseCborHealthPayload(buf, deviceId)

  try {
    await insertHealthRecord(deviceId, health as unknown as Record<string, unknown>)
    await upsertDeviceState(deviceId, {
      sensor_data: {
        rssi:       health.rssi,
        free_heap:  health.free_heap,
        uptime_sec: health.uptime_sec,
        rtt_ms:     health.rtt_ms,
      }
    })
    console.log(`[health] ${deviceId}: heap=${health.free_heap}, rssi=${health.rssi}, rtt=${health.rtt_ms}ms`)
  } catch (err) {
    console.error('[health] DB insert error:', err)
  }

  if (health.free_heap !== undefined && health.free_heap < 50000) {
    console.warn(`[health] ALERT: ${deviceId} low heap ${health.free_heap} bytes`)
    await logAudit(deviceId, 'HEAP_LOW_ALERT', health as unknown as Record<string, unknown>)
  }
  if (health.rssi !== undefined && health.rssi < -85) {
    console.warn(`[health] ALERT: ${deviceId} weak signal ${health.rssi} dBm`)
  }
}

/**
 * S1 fix: Parse health payload as CBOR indefinite map.
 * Firmware health_service.c encodes:
 *   KEY_FLOAT(cpu_usage) KEY_INT(free_heap) KEY_INT(min_heap)
 *   KEY_RSSI(rssi) KEY_FLOAT(temperature) KEY_FLOAT(packet_loss)
 *   KEY_FLOAT(rtt_ms) KEY_TIMESTAMP(uptime_sec) KEY_INT(restart_count)
 *   KEY_STR(device_id) KEY_STR(firmware_ver)
 */
function parseCborHealthPayload(buf: Buffer, deviceId: string): HealthReportData {
  const entries = parseTlv(buf)
  const result: HealthReportData = { device_id: deviceId, recorded_at: new Date().toISOString() }

  /* Track float and int field order — firmware uses same key type for sequential fields */
  let floatIdx = 0
  let intIdx   = 0
  const floatKeys: (keyof HealthReportData)[] = ['cpu_usage', 'temperature', 'packet_loss', 'rtt_ms']
  const intKeys:   (keyof HealthReportData)[] = ['free_heap', 'min_heap', 'restart_count']

  for (const e of entries) {
    switch (e.type as TlvType) {
      case TlvType.VALUE_FLOAT:
        if (floatIdx < floatKeys.length)
          (result as any)[floatKeys[floatIdx++]] = tlvReadFloat(e)
        break
      case TlvType.VALUE_INT:
        if (intIdx < intKeys.length)
          (result as any)[intKeys[intIdx++]] = tlvReadUInt32(e)
        break
      case TlvType.RSSI:
        result.rssi = tlvReadInt8(e)
        break
      case TlvType.TIMESTAMP:
        result.uptime_sec = tlvReadUInt32(e)
        break
      case TlvType.DEVICE_ID:
        result.device_id = tlvReadString(e)
        break
    }
  }
  return result
}
