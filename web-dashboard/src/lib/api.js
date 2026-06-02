const BASE_URL = import.meta.env.VITE_YKP_SERVER_URL || 'https://ykp-router.onrender.com'

export async function fetchDevices() {
  const res = await fetch(`${BASE_URL}/api/devices`)
  if (!res.ok) throw new Error('Failed to fetch devices')
  return res.json()
}

export async function fetchDevice(deviceId) {
  const res = await fetch(`${BASE_URL}/api/devices/${deviceId}`)
  if (!res.ok) throw new Error('Failed to fetch device')
  return res.json()
}

export async function sendCommand(deviceId, service, action) {
  const res = await fetch(`${BASE_URL}/api/devices/${deviceId}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ service, action }),
  })
  if (!res.ok) throw new Error('Failed to send command')
  return res.json()
}

export async function fetchHealth(deviceId) {
  const res = await fetch(`${BASE_URL}/api/health/${deviceId}`)
  if (!res.ok) throw new Error('Failed to fetch health')
  return res.json()
}

export async function fetchHealthHistory(deviceId, hours = 24) {
  const res = await fetch(`${BASE_URL}/api/health/${deviceId}/history?hours=${hours}`)
  if (!res.ok) throw new Error('Failed to fetch health history')
  return res.json()
}
