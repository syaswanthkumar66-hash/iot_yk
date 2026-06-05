import { YkpPacket } from '../packet/parser'
import { buildPacket, TlvBuilder } from '../packet/builder'
import { RouteType, ServiceId, RelayAction, TlvType, QoS } from '../packet/constants'
import { sendYkpPacket } from '../router/route-engine'
import { logAudit, upsertDeviceState } from '../db/supabase'
import { appEvents } from '../events'

export async function handleRelayAck(pkt: YkpPacket): Promise<void> {
  const deviceId = pkt.header.sourceId
  const state    = pkt.payload.length > 3 ? pkt.payload[3] : 0
  console.log(`[relay] ACK from ${deviceId}: state=${state === 1 ? 'ON' : 'OFF'}`)
  await logAudit(deviceId, `RELAY_${state === 1 ? 'ON' : 'OFF'}_ACK`, { state })
  await upsertDeviceState(deviceId, { relay_state: state === 1 })
  appEvents.emit('device_state_changed', { deviceId, relay_state: state === 1 })
}

export function sendRelayCommand(
  sourceId:  string,
  deviceId:  string,
  action:    RelayAction,
  sessionId: number,
  packetId:  number,
): void {
  const sent = sendYkpPacket(deviceId, {
    packetId,
    sessionId,
    sourceId,
    destId:    deviceId,
    routeType: RouteType.DIRECT,
    serviceId: ServiceId.RELAY,
    actionId:  action,
    qos:       QoS.QOS_2,
  })
  if (!sent) console.warn(`[relay] ${deviceId} offline — command queued`)
}
