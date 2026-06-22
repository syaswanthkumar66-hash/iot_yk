import { YKP_HEADER_SIZE, YKP_AUTH_TAG_SIZE, YKP_MIN_PACKET,
         YKP_MAGIC, YKP_VERSION, YKP_DEVICE_ID_LEN, TlvType } from './constants'

export interface YkpHeader {
  magic:       Buffer
  version:     number
  flags:       number
  packetId:    number
  sessionId:   number
  sourceId:    string
  destId:      string
  routeType:   number
  serviceId:   number
  actionId:    number
  payloadLen:  number
}

export interface YkpPacket {
  header:  YkpHeader
  payload: Buffer   // encrypted or plaintext TLV bytes
  authTag: Buffer   // 16-byte GCM tag
  raw:     Buffer   // original full packet
}

export interface TlvEntry {
  type:   number
  length: number
  value:  Buffer
}

/** Parse raw binary buffer into YkpPacket. Returns null on error. */
export function parsePacket(raw: Buffer): YkpPacket | null {
  if (raw.length < YKP_MIN_PACKET) return null
  if (raw[0] !== YKP_MAGIC[0] || raw[1] !== YKP_MAGIC[1]) return null
  if (raw[2] !== YKP_VERSION) return null

  const payloadLen = raw.readUInt16BE(31)
  const expected   = YKP_HEADER_SIZE + payloadLen + YKP_AUTH_TAG_SIZE
  if (raw.length < expected) return null

  const header: YkpHeader = {
    magic:      raw.subarray(0, 2),
    version:    raw[2],
    flags:      raw[3],
    packetId:   raw.readUInt32BE(4),
    sessionId:  raw.readUInt32BE(8),
    sourceId:   raw.subarray(12, 20).toString('utf8').replace(/\0/g, '').trim(),
    destId:     raw.subarray(20, 28).toString('utf8').replace(/\0/g, '').trim(),
    routeType:  raw[28],
    serviceId:  raw[29],
    actionId:   raw[30],
    payloadLen,
  }

  const payload = raw.subarray(YKP_HEADER_SIZE, YKP_HEADER_SIZE + payloadLen)
  const authTag = raw.subarray(YKP_HEADER_SIZE + payloadLen,
                               YKP_HEADER_SIZE + payloadLen + YKP_AUTH_TAG_SIZE)
  return { header, payload, authTag, raw }
}

/** Parse CBOR map payload */
export function parseTlv(buf: Buffer): TlvEntry[] {
  const entries: TlvEntry[] = []
  if (buf.length < 2) return entries

  let i = 0
  const header = buf[i++]
  const indefinite = (header === 0xBF)

  while (i < buf.length) {
    if (indefinite && buf[i] === 0xFF) {
      break
    }

    let key = 0
    const keyHdr = buf[i++]
    if (keyHdr < 24) {
      key = keyHdr
    } else if (keyHdr === 0x18) {
      key = buf[i++]
    } else {
      break
    }

    const valStart = i
    const valHdr = buf[i]
    i++

    const major = valHdr & 0xE0
    const info = valHdr & 0x1F

    if (major === 0x00 || major === 0x20) {
      if (info < 24) {}
      else if (info === 0x18) i += 1
      else if (info === 0x19) i += 2
      else if (info === 0x1A) i += 4
      else if (info === 0x1B) i += 8
    } else if (major === 0x40 || major === 0x60) {
      let strLen = 0
      if (info < 24) strLen = info
      else if (info === 0x18) strLen = buf[i++]
      else if (info === 0x19) { strLen = buf.readUInt16BE(i); i += 2 }
      i += strLen
    } else if (major === 0xE0) {
      if (info === 25) i += 2
      else if (info === 26) i += 4
      else if (info === 27) i += 8
    }

    const value = buf.subarray(valStart, i)
    entries.push({ type: key, length: value.length, value })
  }

  return entries
}

export function findTlv(buf: Buffer, type: number): TlvEntry | null {
  const entries = parseTlv(buf)
  return entries.find(e => e.type === type) ?? null
}

export function tlvReadString(entry: TlvEntry): string {
  const buf = entry.value
  const valHdr = buf[0]
  const info = valHdr & 0x1F
  let i = 1
  let strLen = 0
  if (info < 24) strLen = info
  else if (info === 0x18) strLen = buf[i++]
  else if (info === 0x19) { strLen = buf.readUInt16BE(i); i += 2 }
  return buf.subarray(i, i + strLen).toString('utf8')
}

export function tlvReadUInt32(entry: TlvEntry): number {
  const buf = entry.value
  const valHdr = buf[0]
  const info = valHdr & 0x1F
  if (info < 24) return info
  if (info === 0x18) return buf[1]
  if (info === 0x19) return buf.readUInt16BE(1)
  if (info === 0x1A) return buf.readUInt32BE(1)
  return 0
}

export function tlvReadUInt16(entry: TlvEntry): number {
  return tlvReadUInt32(entry)
}

export function tlvReadInt8(entry: TlvEntry): number {
  const buf = entry.value
  const valHdr = buf[0]
  const major = valHdr & 0xE0
  const info = valHdr & 0x1F
  if (major === 0x00) {
    return tlvReadUInt32(entry)
  } else if (major === 0x20) {
    let uv = 0
    if (info < 24) uv = info
    else if (info === 0x18) uv = buf[1]
    else if (info === 0x19) uv = buf.readUInt16BE(1)
    else if (info === 0x1A) uv = buf.readUInt32BE(1)
    return -1 - uv
  }
  return 0
}

function decodeFloat16(binary: number): number {
  const exponent = (binary & 0x7c00) >> 10;
  const fraction = binary & 0x03ff;
  const sign = (binary & 0x8000) ? -1 : 1;
  if (exponent === 0) {
    return sign * Math.pow(2, -14) * (fraction / 1024);
  } else if (exponent === 0x1f) {
    return fraction ? NaN : sign * Math.pow(2, -15);
  }
  return sign * Math.pow(2, exponent - 15) * (1 + fraction / 1024);
}

export function tlvReadFloat(entry: TlvEntry): number {
  const buf = entry.value
  const hdr = buf[0]
  if (hdr === 0xF9) {
    const f16 = buf.readUInt16BE(1)
    return parseFloat(decodeFloat16(f16).toFixed(2))
  } else if (hdr === 0xFA) {
    return parseFloat(buf.readFloatBE(1).toFixed(2))
  }
  return 0
}
