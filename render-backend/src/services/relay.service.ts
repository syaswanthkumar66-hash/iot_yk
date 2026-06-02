import { YkpPacket } from '../packet/parser'
import { buildPacket, TlvBuilder } from '../packet/builder'
import { RouteType, ServiceId, RelayAction, TlvType, QoS } from '../packet/constants'
import { sendToDevice } from '../router/route-engine'
import { logAudit } from '../db/supabase'

export async function handleRelayAck(pkt: YkpPacket): Promise<void> {
  const deviceId = pkt.header.sourceId
  const state    = pkt.payload.length > 3 ? pkt.payload[3] : 0
  console.log(`[relay] ACK from ${deviceId}: state=${state === 1 ? 'ON' : 'OFF'}`)
  await logAudit(deviceId, `RELAY_${state === 1 ? 'ON' : 'OFF'}_ACK`, { state })
}

export function sendRelayCommand(
  sourceId:  string,
  deviceId:  string,
  action:    RelayAction,
  sessionId: number,
  packetId:  number,
): void {
  const pkt = buildPacket({
    packetId,
    sessionId,
    sourceId,
    destId:    deviceId,
    routeType: RouteType.DIRECT,
    serviceId: ServiceId.RELAY,
    actionId:  action,
    qos:       QoS.QOS_2,
  })
  const sent = sendToDevice(deviceId, pkt)
  if (!sent) console.warn(`[relay] ${deviceId} offline — command queued`)
}
