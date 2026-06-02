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

/** Parse all TLV entries from a buffer */
export function parseTlv(buf: Buffer): TlvEntry[] {
  const entries: TlvEntry[] = []
  let i = 0
  while (i + 3 <= buf.length) {
    const type   = buf[i]
    const length = buf.readUInt16BE(i + 1)
    if (i + 3 + length > buf.length) break
    entries.push({ type, length, value: buf.subarray(i + 3, i + 3 + length) })
    i += 3 + length
  }
  return entries
}

/** Find a specific TLV type */
export function findTlv(buf: Buffer, type: TlvType): TlvEntry | null {
  const entries = parseTlv(buf)
  return entries.find(e => e.type === type) ?? null
}

export function tlvReadString(entry: TlvEntry): string {
  return entry.value.toString('utf8')
}

export function tlvReadUInt32(entry: TlvEntry): number {
  return entry.value.readUInt32BE(0)
}

export function tlvReadUInt16(entry: TlvEntry): number {
  return entry.value.readUInt16BE(0)
}

export function tlvReadFloat(entry: TlvEntry): number {
  return entry.value.readFloatBE(0)
}

export function tlvReadInt8(entry: TlvEntry): number {
  return entry.value.readInt8(0)
}
