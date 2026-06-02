import { setDeviceOnline } from '../db/supabase'

export class PresenceEngine {
  private heartbeats = new Map<string, NodeJS.Timeout>()

  async markOnline(deviceId: string, ip: string): Promise<void> {
    await setDeviceOnline(deviceId, ip, true)
    console.log(`[presence] ${deviceId} ONLINE (${ip})`)

    // Refresh last_seen every 60 seconds while connected
    this.heartbeats.set(deviceId, setInterval(async () => {
      await setDeviceOnline(deviceId, ip, true)
    }, 60_000))
  }

  async markOffline(deviceId: string): Promise<void> {
    const timer = this.heartbeats.get(deviceId)
    if (timer) { clearInterval(timer); this.heartbeats.delete(deviceId) }
    await setDeviceOnline(deviceId, '', false)
    console.log(`[presence] ${deviceId} OFFLINE`)
  }

  getOnlineCount(): number {
    return this.heartbeats.size
  }
}

export const presenceEngine = new PresenceEngine()
