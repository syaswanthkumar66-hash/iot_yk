import crypto from 'crypto'

const IV_LEN  = 12
const TAG_LEN = 16

/**
 * AES-256-GCM encrypt.
 * IV = sessionId(4) || packetId(4) || random(4)
 * AAD = YKP header (33 bytes)
 * Returns { ciphertext, authTag, iv }
 */
export function encrypt(
  sessionKey: Buffer,
  sessionId:  number,
  packetId:   number,
  aad:        Buffer,
  plaintext:  Buffer,
): { ciphertext: Buffer; authTag: Buffer; iv: Buffer } {
  const iv = Buffer.alloc(IV_LEN)
  iv.writeUInt32BE(sessionId, 0)
  iv.writeUInt32BE(packetId,  4)
  iv.writeUInt32BE(packetId,  8) // Deterministic, unique per packet to prevent IV reuse

  const cipher = crypto.createCipheriv('aes-256-gcm', sessionKey, iv)
  cipher.setAAD(aad)

  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()])
  const authTag    = cipher.getAuthTag()

  return { ciphertext, authTag, iv }
}

/**
 * AES-256-GCM decrypt.
 * Returns decrypted plaintext or throws on auth failure.
 */
export function decrypt(
  sessionKey:  Buffer,
  sessionId:   number,
  packetId:    number,
  aad:         Buffer,
  ciphertext:  Buffer,
  authTag:     Buffer,
): Buffer {
  const iv = Buffer.alloc(IV_LEN)
  iv.writeUInt32BE(sessionId, 0)
  iv.writeUInt32BE(packetId,  4)
  iv.writeUInt32BE(packetId,  8) // Match deterministic IV used in encryption

  const decipher = crypto.createDecipheriv('aes-256-gcm', sessionKey, iv)
  decipher.setAAD(aad)
  decipher.setAuthTag(authTag)

  try {
    return Buffer.concat([decipher.update(ciphertext), decipher.final()])
  } catch {
    throw new Error('AES-GCM auth tag mismatch — possible tamper or replay')
  }
}
