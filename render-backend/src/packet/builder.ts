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

/** TLV builder — returns built buffer */
export class TlvBuilder {
  private chunks: Buffer[] = []

  addUInt8(type: TlvType, val: number): this {
    const b = Buffer.alloc(4)
    b[0] = type; b.writeUInt16BE(1, 1); b[3] = val
    this.chunks.push(b); return this
  }

  addUInt16(type: TlvType, val: number): this {
    const b = Buffer.alloc(5)
    b[0] = type; b.writeUInt16BE(2, 1); b.writeUInt16BE(val, 3)
    this.chunks.push(b); return this
  }

  addUInt32(type: TlvType, val: number): this {
    const b = Buffer.alloc(7)
    b[0] = type; b.writeUInt16BE(4, 1); b.writeUInt32BE(val, 3)
    this.chunks.push(b); return this
  }

  addFloat(type: TlvType, val: number): this {
    const b = Buffer.alloc(7)
    b[0] = type; b.writeUInt16BE(4, 1); b.writeFloatBE(val, 3)
    this.chunks.push(b); return this
  }

  addString(type: TlvType, val: string): this {
    const str = Buffer.from(val, 'utf8')
    const b   = Buffer.alloc(3 + str.length)
    b[0] = type; b.writeUInt16BE(str.length, 1)
    str.copy(b, 3)
    this.chunks.push(b); return this
  }

  addBytes(type: TlvType, val: Buffer): this {
    const b = Buffer.alloc(3 + val.length)
    b[0] = type; b.writeUInt16BE(val.length, 1)
    val.copy(b, 3)
    this.chunks.push(b); return this
  }

  addBigInt(type: TlvType, val: bigint): this {
    const b = Buffer.alloc(11)
    b[0] = type; b.writeUInt16BE(8, 1)
    b.writeBigUInt64BE(val, 3)
    this.chunks.push(b); return this
  }

  build(): Buffer {
    return Buffer.concat(this.chunks)
  }
}
