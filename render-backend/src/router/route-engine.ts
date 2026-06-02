import { WebSocket } from 'ws'
import { buildPacket } from '../packet/builder'
import { RouteType, ServiceId, QoS } from '../packet/constants'

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

export function getOnlineDevices(): string[] {
  return [...deviceConnections.keys()]
}
