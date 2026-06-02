import crypto from 'crypto'
import { createHmac, createHash } from 'crypto'

export interface SessionKeys {
  sessionKey: Buffer   // 32 bytes AES-256
  sessionId:  number
}

/**
 * Generate server ephemeral ECDH key pair (P-256)
 */
export function generateEphemeralKeyPair() {
  const ecdh = crypto.createECDH('prime256v1')
  ecdh.generateKeys()
  return {
    privateKey:  ecdh.getPrivateKey(),
    publicKey:   ecdh.getPublicKey(),  // 65-byte uncompressed
    ecdhInstance: ecdh,
  }
}

/**
 * Derive session AES-256 key from ECDH shared secret using HKDF-SHA256
 */
export function deriveSessionKey(
  ecdhPrivateKey: Buffer,
  clientPublicKey: Buffer,
  nonce: Buffer,
  sessionId: number,
): Buffer {
  const ecdh = crypto.createECDH('prime256v1')
  ecdh.setPrivateKey(ecdhPrivateKey)
  const sharedSecret = ecdh.computeSecret(clientPublicKey)

  // HKDF-SHA256
  const info = Buffer.from('ykp-session-v5', 'utf8')
  return hkdf(sharedSecret, nonce, info, 32)
}

/** HKDF-SHA256 (RFC 5869) */
function hkdf(ikm: Buffer, salt: Buffer, info: Buffer, len: number): Buffer {
  // Extract
  const prk = createHmac('sha256', salt).update(ikm).digest()

  // Expand
  const blocks: Buffer[] = []
  let prev = Buffer.alloc(0)
  let remaining = len
  let counter   = 1

  while (remaining > 0) {
    const hmac = createHmac('sha256', prk)
    hmac.update(prev)
    hmac.update(info)
    hmac.update(Buffer.from([counter++]))
    prev = hmac.digest()
    blocks.push(prev)
    remaining -= prev.length
  }
  return Buffer.concat(blocks).subarray(0, len)
}

/** Generate a random nonce */
export function generateNonce(len = 16): Buffer {
  return crypto.randomBytes(len)
}

/** Generate a random session ID */
export function generateSessionId(): number {
  return crypto.randomInt(0x00000001, 0xFFFFFFFF)
}

/** SHA-256 hash */
export function sha256(data: Buffer): Buffer {
  return createHash('sha256').update(data).digest()
}
