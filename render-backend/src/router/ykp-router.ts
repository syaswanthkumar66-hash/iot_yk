import { WebSocket, WebSocketServer } from 'ws'
import { IncomingMessage } from 'http'
import { parsePacket, findTlv } from '../packet/parser'
import { buildPacket, TlvBuilder } from '../packet/builder'
import { sessionManager } from './session-manager'
import { registerConnection, removeConnection } from './route-engine'
import { presenceEngine } from '../presence/presence-engine'
import { handleHealthReport } from '../services/health.service'
import { handleRelayAck } from '../services/relay.service'
import { handleSensorReport } from '../services/sensor.service'
import { generateEphemeralKeyPair, generateNonce,
         generateSessionId, deriveSessionKey } from '../security/ecdh'
import { logAudit, upsertDevice } from '../db/supabase'
import {
  ServiceId, SecAction, RelayAction, HealthAction,
  SensorAction, OtaAction, RouteType, QoS, TlvType, FLAGS, YKP_HEADER_SIZE
} from '../packet/constants'
import { decrypt } from '../security/aes-gcm'

export function startYkpRouter(wss: WebSocketServer): void {
  wss.on('connection', (ws: WebSocket, req: IncomingMessage) => {
    const ip = req.socket.remoteAddress ?? ''
    let deviceId = ''
    console.log(`[ws] new connection from ${ip}`)

    ws.on('message', async (data: Buffer) => {
      try {
        const pkt = parsePacket(data)
        if (!pkt) {
          console.warn(`[ws] invalid packet from ${ip}`)
          return
        }

        const srcId     = pkt.header.sourceId
        const serviceId = pkt.header.serviceId as ServiceId
        const actionId  = pkt.header.actionId

        // ── Security service (handshake) ──────────────
        if (serviceId === ServiceId.SECURITY) {

          if (actionId === SecAction.HELLO) {
            deviceId = srcId
            console.log(`[auth] HELLO from ${deviceId}`)

            // Register device in Supabase
            await upsertDevice(deviceId, { last_seen: new Date().toISOString() })

            // Extract client ephemeral public key from payload
            const pubKeyEntry = findTlv(pkt.payload, TlvType.PUBLIC_KEY)
            if (!pubKeyEntry) {
              console.error(`[auth] No public key TLV found in HELLO from ${deviceId}`)
              ws.close(4002, 'No public key TLV')
              return
            }
            const clientPubKey = pubKeyEntry.value

            // Generate server ephemeral key pair
            const { privateKey, publicKey } = generateEphemeralKeyPair()
            const nonce = generateNonce(16)

            sessionManager.createPendingSession(deviceId, privateKey, publicKey, nonce, clientPubKey)

            // Build CHALLENGE
            const payload = new TlvBuilder()
              .addBytes(TlvType.NONCE,      nonce)
              .addBytes(TlvType.PUBLIC_KEY, publicKey)
              .build()

            const challenge = buildPacket({
              packetId:  pkt.header.packetId,
              sessionId: 0,
              sourceId:  'SERVER',
              destId:    deviceId,
              routeType: RouteType.DIRECT,
              serviceId: ServiceId.SECURITY,
              actionId:  SecAction.CHALLENGE,
              qos:       QoS.QOS_1,
              payload,
            })
            ws.send(challenge)
            return
          }

          if (actionId === SecAction.ECDH_RESPONSE) {
            const session = sessionManager.getSession(deviceId)
            if (!session) { ws.close(4001, 'No pending session'); return }

            // Derive session key from our private + client's public ephemeral
            const sessionKey = deriveSessionKey(
              session.ephemeralPriv,
              session.clientPubKey,
              session.nonce,
              session.sessionId
            )
            sessionManager.activateSession(deviceId, sessionKey)

            // Register WS connection
            registerConnection(deviceId, ws)
            await presenceEngine.markOnline(deviceId, ip)

            // Build SESSION_ACTIVE
            const payload = new TlvBuilder()
              .addUInt32(TlvType.VALUE_INT, session.sessionId)
              .build()

            const sessActive = buildPacket({
              packetId:  pkt.header.packetId + 1,
              sessionId: session.sessionId,
              sourceId:  'SERVER',
              destId:    deviceId,
              routeType: RouteType.DIRECT,
              serviceId: ServiceId.SECURITY,
              actionId:  SecAction.SESSION_ACTIVE,
              qos:       QoS.QOS_1,
              payload,
            })
            ws.send(sessActive)
            console.log(`[auth] SESSION_ACTIVE sent to ${deviceId}`)
            await logAudit(deviceId, 'SESSION_ACTIVE', { ip })
            return
          }
        }

        // ── Must be authenticated for all other services ──
        if (!sessionManager.isActive(deviceId)) {
          console.warn(`[ws] unauthenticated packet from ${deviceId}`)
          return
        }

        // Replay check
        const session = sessionManager.getSession(deviceId)!
        if (!session.replayGuard.check(pkt.header.packetId)) {
          console.warn(`[replay] blocked from ${deviceId} id=${pkt.header.packetId}`)
          return
        }

        // ── Decrypt Payload if ENCRYPTED ─────────────────
        if ((pkt.header.flags & FLAGS.ENCRYPTED) !== 0) {
          try {
            const aad = data.subarray(0, YKP_HEADER_SIZE)
            const plaintext = decrypt(
              session.sessionKey,
              /* S2 fix: pass session.sessionId as key_ver — matches firmware's first key_ver */
              session.sessionId,
              pkt.header.packetId,
              aad,
              pkt.payload,
              pkt.authTag
            )
            pkt.payload = plaintext
          } catch (err: any) {
            console.error(`[decrypt] Failed for ${deviceId}: ${err.message}`)
            return
          }
        }

        // ── Auto ACK for QoS 1 packets ──────────────────
        const qos = pkt.header.flags & FLAGS.QOS_MASK
        if (qos === QoS.QOS_1) {
          /* S4 fix: use session packetCounter for server outbound packets */
          const ackPktId = ++session.packetCounter
          const ackPkt = buildPacket({
            packetId:  ackPktId,
            sessionId: session.sessionId,
            sourceId:  'SERVER',
            destId:    deviceId,
            routeType: RouteType.DIRECT,
            serviceId: serviceId,
            actionId:  actionId,
            qos:       QoS.QOS_0,
            ack:       true,
          })
          ws.send(ackPkt)
        }

        // ── Dispatch to services ──────────────────────
        switch (serviceId) {
          case ServiceId.HEALTH:
            if (actionId === HealthAction.REPORT)
              await handleHealthReport(pkt)
            break

          case ServiceId.RELAY:
            if (actionId === RelayAction.ACK)
              await handleRelayAck(pkt)
            break

          case ServiceId.SENSOR:
            await handleSensorReport(pkt)
            break

          case ServiceId.OTA:
            console.log(`[ota] action=0x${actionId.toString(16)} from ${deviceId}`)
            break

          case ServiceId.DISCOVERY:
            console.log(`[discovery] request from ${deviceId}`)
            break

          default:
            console.warn(`[ws] unknown service 0x${serviceId.toString(16)}`)
        }

      } catch (err) {
        console.error('[ws] packet handler error:', err)
      }
    })

    ws.on('close', async () => {
      if (deviceId) {
        removeConnection(deviceId)
        sessionManager.closeSession(deviceId)
        await presenceEngine.markOffline(deviceId)
        console.log(`[ws] ${deviceId} disconnected`)
      }
    })

    ws.on('error', (err) => {
      console.error(`[ws] error for ${deviceId}:`, err.message)
    })

    // S3 fix: Do NOT send a JSON text greeting.
    // The ESP32 WS parser only accepts binary YKP packets —
    // a UTF-8 JSON string will fail the magic byte check and log errors.
    // Connection is confirmed when the device sends its first HELLO packet.
  })

  console.log('[ykp-router] WebSocket router started')
}
