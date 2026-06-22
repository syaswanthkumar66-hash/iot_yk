import crypto from 'crypto'

const IV_LEN  = 12
const TAG_LEN = 16

/**
 * AES-256-GCM encrypt.
 * IV = key_ver(4) || packetId(4) || 0x00000000(4)
 * Firmware spec: ykp_security.c build_iv uses key_ver in [0..3], packet_id in [4..7], zeros in [8..11]
 * Server uses sessionId as key_ver (single rotation cycle per session).
 * AAD = YKP header (YKP_HEADER_SIZE bytes)
 * Returns { ciphertext, authTag, iv }
 */
export function encrypt(
  sessionKey: Buffer,
  keyVer:     number,   // S2 fix: was sessionId, now explicitly key_ver to match firmware IV
  packetId:   number,
  aad:        Buffer,
  plaintext:  Buffer,
): { ciphertext: Buffer; authTag: Buffer; iv: Buffer } {
  const iv = Buffer.alloc(IV_LEN, 0)
  iv.writeUInt32BE(keyVer,   0)   // bytes 0-3:  key_ver
  iv.writeUInt32BE(packetId, 4)   // bytes 4-7:  packet_id
  // bytes 8-11: zeros (already zero from Buffer.alloc)

  const cipher = crypto.createCipheriv('aes-256-gcm', sessionKey, iv)
  cipher.setAAD(aad)

  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()])
  const authTag    = cipher.getAuthTag()

  return { ciphertext, authTag, iv }
}

/**
 * AES-256-GCM decrypt.
 * IV = key_ver(4) || packetId(4) || 0x00000000(4) — must match firmware exactly.
 * Returns decrypted plaintext or throws on auth failure.
 */
export function decrypt(
  sessionKey:  Buffer,
  keyVer:      number,   // S2 fix: key_ver matches firmware IV slot [0..3]
  packetId:    number,
  aad:         Buffer,
  ciphertext:  Buffer,
  authTag:     Buffer,
): Buffer {
  const iv = Buffer.alloc(IV_LEN, 0)
  iv.writeUInt32BE(keyVer,   0)   // bytes 0-3:  key_ver
  iv.writeUInt32BE(packetId, 4)   // bytes 4-7:  packet_id
  // bytes 8-11: zeros — matches firmware memset(iv+8, 0, 4)

  const decipher = crypto.createDecipheriv('aes-256-gcm', sessionKey, iv)
  decipher.setAAD(aad)
  decipher.setAuthTag(authTag)

  try {
    return Buffer.concat([decipher.update(ciphertext), decipher.final()])
  } catch {
    throw new Error('AES-GCM auth tag mismatch — possible tamper or replay')
  }
}
