import { YKP_MAGIC, YKP_VERSION, YKP_HEADER_SIZE, YKP_AUTH_TAG_SIZE,
         YKP_DEVICE_ID_LEN, RouteType, ServiceId, QoS, TlvType, FLAGS } from './constants'

export interface BuildPacketOptions {
  packetId:  number
  sessionId: number
  sourceId:  string
  destId:    string
  routeType: RouteType
  serviceId: ServiceId
  actionId:  number
  qos?:      QoS
  encrypted?: boolean
  ack?:      boolean
  payload?:  Buffer
  authTag?:  Buffer
}

/** Serialise a YKP v5 packet to binary */
export function buildPacket(opts: BuildPacketOptions): Buffer {
  const payload   = opts.payload ?? Buffer.alloc(0)
  const authTag   = opts.authTag ?? Buffer.alloc(YKP_AUTH_TAG_SIZE)
  const totalLen  = YKP_HEADER_SIZE + payload.length + YKP_AUTH_TAG_SIZE
  const buf       = Buffer.alloc(totalLen)
  let   offset    = 0

  // Magic + version + flags
  buf[offset++] = YKP_MAGIC[0]
  buf[offset++] = YKP_MAGIC[1]
  buf[offset++] = YKP_VERSION
  let flags = (opts.qos ?? QoS.QOS_0) & FLAGS.QOS_MASK
  if (opts.encrypted) flags |= FLAGS.ENCRYPTED
  if (opts.ack) flags |= FLAGS.ACK
  buf[offset++] = flags

  // packetId, sessionId
  buf.writeUInt32BE(opts.packetId,  offset); offset += 4
  buf.writeUInt32BE(opts.sessionId, offset); offset += 4

  // sourceId (8 bytes, null-padded)
  const srcBuf = Buffer.alloc(YKP_DEVICE_ID_LEN)
  srcBuf.write(opts.sourceId.slice(0, YKP_DEVICE_ID_LEN), 'utf8')
  srcBuf.copy(buf, offset); offset += YKP_DEVICE_ID_LEN

  // destId
  const dstBuf = Buffer.alloc(YKP_DEVICE_ID_LEN)
  dstBuf.write(opts.destId.slice(0, YKP_DEVICE_ID_LEN), 'utf8')
  dstBuf.copy(buf, offset); offset += YKP_DEVICE_ID_LEN

  // routeType, serviceId, actionId
  buf[offset++] = opts.routeType
  buf[offset++] = opts.serviceId
  buf[offset++] = opts.actionId

  // payload length
  buf.writeUInt16BE(payload.length, offset); offset += 2

  // payload
  payload.copy(buf, offset); offset += payload.length

  // authTag
  authTag.copy(buf, offset)

  return buf
}

function float32ToFloat16(val: number): number {
  const buf = Buffer.alloc(4)
  buf.writeFloatBE(val, 0)
  const f = buf.readUInt32BE(0)
  const sign = (f >> 16) & 0x8000
  const exponent = ((f >> 23) & 0xFF) - 127
  const mantissa = f & 0x007FFFFF

  if (((f >> 23) & 0xFF) === 0) return sign
  if (((f >> 23) & 0xFF) === 0xFF) return sign | 0x7C00 | (mantissa ? 0x0200 : 0)

  const newExp = exponent + 15
  if (newExp >= 31) return sign | 0x7C00
  if (newExp <= 0) {
    if (newExp < -10) return sign
    const subMantissa = (mantissa | 0x00800000) >> (1 - newExp)
    return sign | (subMantissa >> 13)
  }
  return sign | (newExp << 10) | (mantissa >> 13)
}

/** CBOR Indefinite Map Builder (equivalent to legacy TlvBuilder) */
export class TlvBuilder {
  private chunks: Buffer[] = []

  constructor() {
    // CBOR Indefinite map start
    this.chunks.push(Buffer.from([0xBF]))
  }

  private addKey(key: number) {
    if (key < 24) {
      this.chunks.push(Buffer.from([key]))
    } else {
      this.chunks.push(Buffer.from([0x18, key]))
    }
  }

  addUInt8(type: TlvType, val: number): this {
    this.addKey(type)
    if (val < 24) {
      this.chunks.push(Buffer.from([val]))
    } else {
      this.chunks.push(Buffer.from([0x18, val]))
    }
    return this
  }

  addUInt16(type: TlvType, val: number): this {
    this.addKey(type)
    if (val < 24) {
      this.chunks.push(Buffer.from([val]))
    } else if (val <= 0xFF) {
      this.chunks.push(Buffer.from([0x18, val]))
    } else {
      const b = Buffer.alloc(3)
      b[0] = 0x19
      b.writeUInt16BE(val, 1)
      this.chunks.push(b)
    }
    return this
  }

  addUInt32(type: TlvType, val: number): this {
    this.addKey(type)
    if (val < 24) {
      this.chunks.push(Buffer.from([val]))
    } else if (val <= 0xFF) {
      this.chunks.push(Buffer.from([0x18, val]))
    } else if (val <= 0xFFFF) {
      const b = Buffer.alloc(3)
      b[0] = 0x19
      b.writeUInt16BE(val, 1)
      this.chunks.push(b)
    } else {
      const b = Buffer.alloc(5)
      b[0] = 0x1A
      b.writeUInt32BE(val, 1)
      this.chunks.push(b)
    }
    return this
  }

  addFloat(type: TlvType, val: number): this {
    this.addKey(type)
    const f16 = float32ToFloat16(val)
    const b = Buffer.alloc(3)
    b[0] = 0xF9
    b.writeUInt16BE(f16, 1)
    this.chunks.push(b)
    return this
  }

  addString(type: TlvType, val: string): this {
    this.addKey(type)
    const strBuf = Buffer.from(val, 'utf8')
    const len = strBuf.length
    if (len < 24) {
      this.chunks.push(Buffer.concat([Buffer.from([0x60 | len]), strBuf]))
    } else {
      const b = Buffer.alloc(2)
      b[0] = 0x78
      b[1] = len
      this.chunks.push(Buffer.concat([b, strBuf]))
    }
    return this
  }

  addBytes(type: TlvType, val: Buffer): this {
    this.addKey(type)
    const len = val.length
    if (len < 24) {
      this.chunks.push(Buffer.concat([Buffer.from([0x40 | len]), val]))
    } else {
      const b = Buffer.alloc(2)
      b[0] = 0x58
      b[1] = len
      this.chunks.push(Buffer.concat([b, val]))
    }
    return this
  }

  addBigInt(type: TlvType, val: bigint): this {
    this.addKey(type)
    const b = Buffer.alloc(9)
    b[0] = 0x1B
    b.writeBigUInt64BE(val, 1)
    this.chunks.push(b)
    return this
  }

  build(): Buffer {
    // CBOR Indefinite map end
    this.chunks.push(Buffer.from([0xFF]))
    return Buffer.concat(this.chunks)
  }
}
