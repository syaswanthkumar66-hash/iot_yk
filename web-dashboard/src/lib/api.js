export function getBackendUrl() {
  // 1. Check user override in localStorage
  const customUrl = localStorage.getItem('VITE_YKP_SERVER_URL')
  if (customUrl) return customUrl.replace(/\/$/, '')

  // 2. Check build-time env variable
  if (import.meta.env.VITE_YKP_SERVER_URL) {
    return import.meta.env.VITE_YKP_SERVER_URL.replace(/\/$/, '')
  }

  // 3. Render / Production Real Auto Fetch:
  // Detect if we are running in a browser
  if (typeof window !== 'undefined') {
    const origin = window.location.origin
    // If it's the Vite dev port, fall back to backend default local port (10000)
    if (origin.includes('localhost:5173') || origin.includes('127.0.0.1:5173')) {
      return 'http://localhost:10000'
    }
    // If hosted on a cloud environment or custom domain, dynamically auto-fetch the origin!
    return origin
  }

  // Final default fallback
  return 'https://iot-yk.onrender.com'
}

export function getWebSocketUrl() {
  const backendUrl = getBackendUrl()
  // Dynamically map http/https -> ws/wss
  return backendUrl.replace(/^http/, 'ws') + '/ws'
}

export function setBackendUrl(url) {
  if (url) {
    localStorage.setItem('VITE_YKP_SERVER_URL', url.replace(/\/$/, '')) // strip trailing slash
  } else {
    localStorage.removeItem('VITE_YKP_SERVER_URL')
  }
}

export async function fetchDevices() {
  const res = await fetch(`${getBackendUrl()}/api/devices`)
  if (!res.ok) throw new Error('Failed to fetch devices')
  return res.json()
}

export async function fetchDevice(deviceId) {
  const res = await fetch(`${getBackendUrl()}/api/devices/${deviceId}`)
  if (!res.ok) throw new Error('Failed to fetch device')
  return res.json()
}

export async function sendCommand(deviceId, service, action) {
  const res = await fetch(`${getBackendUrl()}/api/devices/${deviceId}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ service, action }),
  })
  if (!res.ok) throw new Error('Failed to send command')
  return res.json()
}

export async function fetchHealth(deviceId) {
  const res = await fetch(`${getBackendUrl()}/api/health/${deviceId}`)
  if (!res.ok) throw new Error('Failed to fetch health')
  return res.json()
}

export async function fetchHealthHistory(deviceId, hours = 24) {
  const res = await fetch(`${getBackendUrl()}/api/health/${deviceId}/history?hours=${hours}`)
  if (!res.ok) throw new Error('Failed to fetch health history')
  return res.json()
}
