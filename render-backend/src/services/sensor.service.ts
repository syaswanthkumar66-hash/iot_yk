import { YkpPacket } from '../packet/parser'
import { parseTlv, tlvReadFloat, tlvReadUInt32 } from '../packet/parser'
import { logAudit, upsertDeviceState } from '../db/supabase'
import { runAutomationEngine } from './automation.service'
import { TlvType } from '../packet/constants'

export interface SensorReportData {
  temperature?: number
  humidity?: number
  motion?: boolean
  light?: number
}

export async function handleSensorReport(pkt: YkpPacket): Promise<void> {
  const deviceId = pkt.header.sourceId
  const buf = pkt.payload

  /* S1 fix: use CBOR parseTlv — firmware sends CBOR indefinite map */
  const sensorData = parseCborSensorPayload(buf)

  try {
    await upsertDeviceState(deviceId, {
      sensor_data: {
        temperature: sensorData.temperature,
        humidity:    sensorData.humidity,
        motion:      sensorData.motion,
        light:       sensorData.light,
      }
    })
    console.log(`[sensor] ${deviceId}: temp=${sensorData.temperature}°C, humidity=${sensorData.humidity}%, motion=${sensorData.motion}, light=${sensorData.light}`)
    await logAudit(deviceId, 'SENSOR_REPORT', sensorData)
    await runAutomationEngine(deviceId, 2, 1, sensorData)
  } catch (err) {
    console.error('[sensor] Error handling report:', err)
  }
}

/**
 * S1 fix: Parse sensor CBOR payload.
 * Firmware sensor_service.c encodes:
 *   KEY_FLOAT(temperature) KEY_FLOAT(humidity) KEY_STATE(motion)
 *   KEY_VALUE_INT(light_raw) KEY_TIMESTAMP(uptime) KEY_STR(device_id)
 */
export function parseCborSensorPayload(buf: Buffer): SensorReportData {
  const entries = parseTlv(buf)
  const result: SensorReportData = {}
  let floatIdx = 0
  const floatKeys: (keyof SensorReportData)[] = ['temperature', 'humidity']

  for (const e of entries) {
    switch (e.type as TlvType) {
      case TlvType.VALUE_FLOAT:
        if (floatIdx < floatKeys.length)
          (result as any)[floatKeys[floatIdx++]] = tlvReadFloat(e)
        break
      case TlvType.STATE:
        result.motion = tlvReadUInt32(e) === 1
        break
      case TlvType.VALUE_INT:
        result.light = tlvReadUInt32(e)
        break
    }
  }
  return result
}
