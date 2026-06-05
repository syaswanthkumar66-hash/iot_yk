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
      return 'http://localhost:8080'
    }
    // If frontend is hosted on the SAME origin as the backend (unified deployment)
    if (origin.includes('iot-yk.onrender.com')) {
      return origin
    }
  }

  // Default fallback for the separate static site: point to the live Render backend worker
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

// ── Auth Helpers ─────────────────────────────────

export function getAuthToken() {
  return localStorage.getItem('ykp_auth_token')
}

export function setAuthToken(token) {
  if (token) {
    localStorage.setItem('ykp_auth_token', token)
  } else {
    localStorage.removeItem('ykp_auth_token')
  }
}

export function isAuthenticated() {
  return !!getAuthToken()
}

export function getAuthHeaders() {
  const token = getAuthToken()
  return {
    'Content-Type': 'application/json',
    ...(token ? { 'Authorization': `Bearer ${token}` } : {})
  }
}

// ── Authentication API ────────────────────────────

export async function login(email, password) {
  const res = await fetch(`${getBackendUrl()}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password })
  })
  
  const data = await res.json()
  if (!res.ok) throw new Error(data.error || 'Failed to log in')
  
  // Store the access token returned from backend Supabase proxy
  if (data?.session?.access_token) {
    setAuthToken(data.session.access_token)
  }
  return data
}

export async function register(email, password) {
  const res = await fetch(`${getBackendUrl()}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password })
  })
  
  const data = await res.json()
  if (!res.ok) throw new Error(data.error || 'Failed to register account')
  return data
}

export function logout() {
  setAuthToken(null)
  window.location.href = '/login'
}

// ── Devices API ───────────────────────────────────

export async function fetchDevices() {
  const res = await fetch(`${getBackendUrl()}/api/devices`, {
    headers: getAuthHeaders()
  })
  if (!res.ok) {
    if (res.status === 401) logout()
    throw new Error('Failed to fetch devices')
  }
  return res.json()
}

export async function fetchDevice(deviceId) {
  const res = await fetch(`${getBackendUrl()}/api/devices/${deviceId}`, {
    headers: getAuthHeaders()
  })
  if (!res.ok) {
    if (res.status === 401) logout()
    throw new Error('Failed to fetch device')
  }
  return res.json()
}

export async function sendCommand(deviceId, service, action) {
  const res = await fetch(`${getBackendUrl()}/api/devices/${deviceId}/command`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify({ service, action }),
  })
  if (!res.ok) {
    if (res.status === 401) logout()
    throw new Error('Failed to send command')
  }
  return res.json()
}

// ── Health API ────────────────────────────────────

export async function fetchHealth(deviceId) {
  const res = await fetch(`${getBackendUrl()}/api/health/${deviceId}`, {
    headers: getAuthHeaders()
  })
  if (!res.ok) {
    if (res.status === 401) logout()
    throw new Error('Failed to fetch health')
  }
  return res.json()
}

export async function fetchHealthHistory(deviceId, hours = 24) {
  const res = await fetch(`${getBackendUrl()}/api/health/${deviceId}/history?hours=${hours}`, {
    headers: getAuthHeaders()
  })
  if (!res.ok) {
    if (res.status === 401) logout()
    throw new Error('Failed to fetch health history')
  }
  return res.json()
}
