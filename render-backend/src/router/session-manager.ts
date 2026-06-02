import { ReplayGuard } from '../security/replay-guard'
import { createSession, invalidateSessions } from '../db/supabase'
import { createHash } from 'crypto'
import { generateSessionId } from '../security/ecdh'

export interface DeviceSession {
  deviceId:      string
  sessionId:     number
  sessionKey:    Buffer          // AES-256 key (keep in memory only)
  replayGuard:   ReplayGuard
  packetCounter: number
  startedAt:     Date
  ephemeralPriv: Buffer          // server ephemeral private key
  ephemeralPub:  Buffer          // server ephemeral public key
  clientPubKey:  Buffer          // client ephemeral public key
  nonce:         Buffer          // challenge nonce
  state:         'hello' | 'challenged' | 'active' | 'closed'
}

export class SessionManager {
  // Map: deviceId → active session
  private sessions = new Map<string, DeviceSession>()

  createPendingSession(deviceId: string,
                       ephemeralPriv: Buffer,
                       ephemeralPub:  Buffer,
                       nonce:         Buffer,
                       clientPubKey:  Buffer): DeviceSession {
    const existing = this.sessions.get(deviceId)
    if (existing) this.sessions.delete(deviceId)

    const session: DeviceSession = {
      deviceId,
      sessionId:      generateSessionId(),
      sessionKey:     Buffer.alloc(32),
      replayGuard:    new ReplayGuard(),
      packetCounter:  0,
      startedAt:      new Date(),
      ephemeralPriv,
      ephemeralPub,
      clientPubKey,
      nonce,
      state: 'hello',
    }
    this.sessions.set(deviceId, session)
    return session
  }

  getSession(deviceId: string): DeviceSession | undefined {
    return this.sessions.get(deviceId)
  }

  activateSession(deviceId: string, sessionKey: Buffer): void {
    const s = this.sessions.get(deviceId)
    if (!s) throw new Error(`No pending session for ${deviceId}`)
    s.sessionKey = sessionKey
    s.state      = 'active'
    s.replayGuard.reset()

    // Persist session key hash to Supabase (never store raw key)
    const keyHash = createHash('sha256').update(sessionKey).digest('hex')
    createSession(deviceId, String(s.sessionId), keyHash).catch(console.error)
  }

  closeSession(deviceId: string): void {
    const s = this.sessions.get(deviceId)
    if (s) {
      s.state = 'closed'
      this.sessions.delete(deviceId)
      invalidateSessions(deviceId).catch(console.error)
    }
  }

  isActive(deviceId: string): boolean {
    return this.sessions.get(deviceId)?.state === 'active'
  }

  getAll(): DeviceSession[] {
    return [...this.sessions.values()]
  }

  count(): number {
    return this.sessions.size
  }
}

export const sessionManager = new SessionManager()
