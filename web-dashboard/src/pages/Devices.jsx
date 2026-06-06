import { useState, useEffect } from 'react'
import { Cpu, Wifi, Search, RefreshCw, ToggleLeft, ToggleRight } from 'lucide-react'
import { fetchDevices, sendCommand } from '../lib/api'
import { supabase } from '../lib/supabase'
import { useSSE } from '../lib/useSSE'

const DEVICE_EMOJI = {
  switch: '🔌',
  sensor: '📡',
  gateway: '🔀',
  motor: '⚙️'
}

export default function Devices() {
  const [devices, setDevices] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('all')

  const { lastStateChange, lastPresenceChange, isConnected } = useSSE()

  const loadDevices = async () => {
    try {
      setLoading(true)
      const data = await fetchDevices()
      setDevices(data)
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  // Load devices on mount
  useEffect(() => {
    loadDevices()
  }, [])

  // Process SSE State Changes
  useEffect(() => {
    if (lastStateChange) {
      setDevices(prev => prev.map(d => 
        d.device_id === lastStateChange.deviceId 
          ? { ...d, relay_state: lastStateChange.relay_state } 
          : d
      ))
    }
  }, [lastStateChange])

  // Process SSE Presence Changes
  useEffect(() => {
    if (lastPresenceChange) {
      setDevices(prev => prev.map(d => 
        d.device_id === lastPresenceChange.deviceId 
          ? { ...d, is_online: lastPresenceChange.is_online } 
          : d
      ))
    }
  }, [lastPresenceChange])

  const toggle = async (id, currentState) => {
    const nextAction = currentState ? 2 : 1 // 1=ON (RELAY_ON), 2=OFF (RELAY_OFF)
    // Optimistic local state update
    setDevices(prev =>
      prev.map(d =>
        d.device_id === id ? { ...d, relay_state: !currentState } : d
      )
    )
    try {
      await sendCommand(id, 1, nextAction) // Service 1 (RELAY)
    } catch (err) {
      console.error('Command failed:', err)
      // Revert state back on error
      setDevices(prev =>
        prev.map(d =>
          d.device_id === id ? { ...d, relay_state: currentState } : d
        )
      )
    }
  }

  const filtered = devices.filter(d => {
    const matchType = filter === 'all' || d.device_type === filter
    const matchSearch =
      (d.device_name && d.device_name.toLowerCase().includes(search.toLowerCase())) ||
      d.device_id.toLowerCase().includes(search.toLowerCase())
    return matchType && matchSearch
  })

  return (
    <div className="page fade-in">
      <div className="flex-between mb-24" style={{ flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', gap: 8 }}>
          {['all', 'switch', 'sensor', 'gateway', 'motor'].map(f => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`btn btn-sm ${filter === f ? 'btn-primary' : 'btn-secondary'}`}
              style={{ textTransform: 'capitalize' }}
            >
              {f}
            </button>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <div style={{ position: 'relative' }}>
            <Search
              size={14}
              style={{
                position: 'absolute',
                left: 10,
                top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-muted)'
              }}
            />
            <input
              className="input"
              style={{ paddingLeft: 32, width: 220 }}
              placeholder="Search devices..."
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <button onClick={loadDevices} className="btn btn-secondary btn-sm">
            <RefreshCw size={14} />Refresh
          </button>
        </div>
      </div>

      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '200px' }}>
          <RefreshCw className="spin" size={24} style={{ color: 'var(--primary)' }} />
          <span style={{ marginLeft: 10, color: 'var(--text-secondary)' }}>Loading live device array...</span>
        </div>
      ) : error ? (
        <div className="alert-banner warning">
          <span>Failed to connect to Render Worker: {error}</span>
        </div>
      ) : filtered.length === 0 ? (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '200px', flexDirection: 'column' }}>
          <span style={{ fontSize: 32, marginBottom: 12 }}>📡</span>
          <span style={{ color: 'var(--text-muted)' }}>No matching registered devices found.</span>
        </div>
      ) : (
        <div className="grid-auto">
          {filtered.map(d => (
            <div key={d.device_id} className="device-card fade-in">
              <div className="device-card-header">
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <div className="device-icon">{DEVICE_EMOJI[d.device_type] || '⚡'}</div>
                  <div>
                    <div className="device-id">{d.device_id}</div>
                    <div className="device-name">{d.device_name || 'Generic Device'}</div>
                  </div>
                </div>
                <span className={`badge ${d.is_online ? 'online' : 'offline'}`}>
                  {d.is_online ? 'Online' : 'Offline'}
                </span>
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <span className={`tag ${d.device_type}`}>{d.device_type.toUpperCase()}</span>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' }}>
                  {d.firmware_ver || 'v1.0.0'}
                </span>
              </div>

              <div style={{ marginBottom: 14 }}>
                <div
                  style={{
                    fontSize: 11,
                    color: 'var(--text-muted)',
                    marginBottom: 4,
                    textTransform: 'uppercase',
                    letterSpacing: '0.5px',
                    fontWeight: 600
                  }}
                >
                  IP Address
                </div>
                <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--text-secondary)' }}>
                  {d.is_online && d.ip_address ? d.ip_address : '—'}
                </div>
              </div>

              <div style={{ marginBottom: 14 }}>
                <div
                  style={{
                    fontSize: 11,
                    color: 'var(--text-muted)',
                    marginBottom: 4,
                    textTransform: 'uppercase',
                    letterSpacing: '0.5px',
                    fontWeight: 600
                  }}
                >
                  Latency (RTT)
                </div>
                <div
                  style={{
                    fontFamily: 'var(--font-mono)',
                    fontSize: 13,
                    color:
                      !d.is_online
                        ? 'var(--text-muted)'
                        : d.rtt_ms > 200
                        ? 'var(--danger)'
                        : d.rtt_ms > 100
                        ? 'var(--warning)'
                        : 'var(--success)'
                  }}
                >
                  {d.is_online && d.rtt_ms !== undefined && d.rtt_ms !== null ? `${d.rtt_ms} ms` : '—'}
                </div>
              </div>

              {d.is_online && d.device_type === 'switch' && (
                <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                  <button
                    onClick={() => toggle(d.device_id, d.relay_state)}
                    className={`btn btn-sm ${d.relay_state ? 'btn-danger' : 'btn-success'}`}
                    style={{ flex: 1 }}
                  >
                    {d.relay_state ? (
                      <>
                        <ToggleRight size={14} /> Turn OFF
                      </>
                    ) : (
                      <>
                        <ToggleLeft size={14} /> Turn ON
                      </>
                    )}
                  </button>
                  {d.rtt_ms !== undefined && d.rtt_ms !== null && (
                    <span
                      style={{
                        fontFamily: 'var(--font-mono)',
                        fontSize: '12px',
                        fontWeight: '600',
                        padding: '4px 8px',
                        borderRadius: '6px',
                        backgroundColor: 'rgba(255, 255, 255, 0.05)',
                        color:
                          d.rtt_ms > 200
                            ? 'var(--danger)'
                            : d.rtt_ms > 100
                            ? 'var(--warning)'
                            : 'var(--success)'
                      }}
                    >
                      {d.rtt_ms} ms
                    </span>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
