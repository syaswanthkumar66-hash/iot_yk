import { WebSocket } from 'ws'
import { buildPacket, BuildPacketOptions } from '../packet/builder'
import { RouteType, ServiceId, QoS, FLAGS, YKP_AUTH_TAG_SIZE, YKP_HEADER_SIZE } from '../packet/constants'
import { sessionManager } from './session-manager'
import { encrypt } from '../security/aes-gcm'

// Map of deviceId → WebSocket connection
export const deviceConnections = new Map<string, WebSocket>()

export function registerConnection(deviceId: string, ws: WebSocket): void {
  deviceConnections.set(deviceId, ws)
  console.log(`[router] registered ${deviceId} (${deviceConnections.size} devices online)`)
}

export function removeConnection(deviceId: string): void {
  deviceConnections.delete(deviceId)
  console.log(`[router] removed ${deviceId} (${deviceConnections.size} devices online)`)
}

export function routePacket(rawBuf: Buffer, destId: string,
                            routeType: RouteType): boolean {
  if (routeType === RouteType.DIRECT || routeType === RouteType.CLOUD) {
    const ws = deviceConnections.get(destId)
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn(`[router] device ${destId} offline — cannot route`)
      return false
    }
    ws.send(rawBuf)
    return true
  }

  if (routeType === RouteType.BROADCAST) {
    let sent = 0
    for (const [id, ws] of deviceConnections) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(rawBuf)
        sent++
      }
    }
    return sent > 0
  }
  return false
}

export function sendToDevice(deviceId: string, buf: Buffer): boolean {
  const ws = deviceConnections.get(deviceId)
  if (!ws || ws.readyState !== WebSocket.OPEN) return false
  ws.send(buf)
  return true
}

export function sendYkpPacket(deviceId: string, opts: BuildPacketOptions): boolean {
  const session = sessionManager.getSession(deviceId)

  if (session && session.sessionKey && opts.payload && opts.payload.length > 0) {
    opts.encrypted = true
    opts.sessionId = session.sessionId
    /* S4 fix: always use monotonically increasing packetCounter for server outbound */
    opts.packetId  = ++session.packetCounter

    /* S7 fix: use YKP_HEADER_SIZE constant, not magic number 33 */
    const emptyPkt = buildPacket({ ...opts, payload: Buffer.alloc(0), authTag: Buffer.alloc(YKP_AUTH_TAG_SIZE) })
    const aad = emptyPkt.subarray(0, YKP_HEADER_SIZE)

    const { ciphertext, authTag } = encrypt(
      session.sessionKey,
      /* S2 fix: pass sessionId as key_ver to match firmware IV spec */
      session.sessionId,
      opts.packetId,
      aad,
      opts.payload
    )

    opts.payload = ciphertext
    opts.authTag = authTag
  } else if (session) {
    opts.sessionId = session.sessionId
    /* S4 fix: increment counter even for unencrypted server packets */
    opts.packetId  = ++session.packetCounter
  }

  const rawBuf = buildPacket(opts)
  return sendToDevice(deviceId, rawBuf)
}

export function getOnlineDevices(): string[] {
  return [...deviceConnections.keys()]
}
