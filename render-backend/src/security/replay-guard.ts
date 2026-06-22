const WINDOW_SIZE = 64

export class ReplayGuard {
  private highestId   = 0
  private windowBits  = BigInt(0)
  private initialised = false

  /** Returns true if packet is valid (not a replay). Registers the ID. */
  check(packetId: number): boolean {
    /* S5 fix: pkt_id=0 is reserved — firmware never emits it, reject it here too */
    if (packetId === 0) return false

    if (!this.initialised) {
      this.highestId   = packetId
      this.windowBits  = BigInt(1)
      this.initialised = true
      return true
    }

    if (packetId > this.highestId) {
      const shift = packetId - this.highestId
      if (shift >= WINDOW_SIZE) {
        this.windowBits = BigInt(1)
      } else {
        this.windowBits = (this.windowBits << BigInt(shift)) | BigInt(1)
      }
      this.highestId = packetId
      return true
    }

    const diff = this.highestId - packetId
    if (diff >= WINDOW_SIZE) return false  // too old

    const bit = BigInt(1) << BigInt(diff)
    if (this.windowBits & bit) return false  // duplicate

    this.windowBits |= bit
    return true
  }

  reset(): void {
    this.highestId   = 0
    this.windowBits  = BigInt(0)
    this.initialised = false
  }
}
