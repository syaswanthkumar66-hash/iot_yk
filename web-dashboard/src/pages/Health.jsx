import { useState, useEffect } from 'react'
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, LineChart, Line, BarChart, Bar } from 'recharts'
import { Activity, Cpu, Wifi, AlertTriangle, RefreshCw } from 'lucide-react'
import { supabase } from '../lib/supabase'

const TOOLTIP_STYLE = {
  contentStyle: { background: 'var(--bg-surface)', border: '1px solid var(--border)', borderRadius: 8 },
  labelStyle: { color: 'var(--text-secondary)' },
  itemStyle: { color: 'var(--text-primary)' }
}

export default function Health() {
  const [devices, setDevices] = useState([])
  const [selected, setSelected] = useState('')
  const [snapshot, setSnapshot] = useState(null)
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [telemetryLoading, setTelemetryLoading] = useState(false)

  // 1. Fetch devices list at boot
  useEffect(() => {
    async function loadDevices() {
      try {
        setLoading(true)
        const { data, error } = await supabase
          .from('devices')
          .select('device_id')
          .order('device_id')

        if (error) throw error
        if (data && data.length > 0) {
          setDevices(data.map(d => d.device_id))
          setSelected(data[0].device_id)
        }
      } catch (err) {
        console.error('Error fetching devices:', err)
      } finally {
        setLoading(false)
      }
    }
    loadDevices()
  }, [])

  // 2. Fetch specific device health when selected device changes
  const loadDeviceHealth = async () => {
    if (!selected) return
    try {
      setTelemetryLoading(true)
      // Get latest snapshot from database
      const { data: snapData, error: snapErr } = await supabase
        .from('device_health')
        .select('*')
        .eq('device_id', selected)
        .order('recorded_at', { ascending: false })
        .limit(1)
        .maybeSingle()

      if (snapErr) throw snapErr
      setSnapshot(snapData)

      // Get historical telemetry points (last 12 records) for graphs
      const { data: histData, error: histErr } = await supabase
        .from('device_health')
        .select('*')
        .eq('device_id', selected)
        .order('recorded_at', { ascending: false })
        .limit(12)

      if (histErr) throw histErr
      if (histData) {
        // Reverse array so time flows left-to-right
        setHistory(
          histData
            .map(h => ({
              t: new Date(h.recorded_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
              heap: Math.round((h.free_heap || 0) / 1024), // Convert to KB
              cpu: h.cpu_usage || 0,
              rssi: h.rssi || 0,
              rtt: h.rtt_ms || 0
            }))
            .reverse()
        )
      }
    } catch (err) {
      console.error('Error loading telemetry:', err)
    } finally {
      setTelemetryLoading(false)
    }
  }

  useEffect(() => {
    loadDeviceHealth()

    // Subscribe to realtime database insertion updates for the selected device
    if (!selected) return
    const channel = supabase
      .channel('live-health-telemetry')
      .on(
        'postgres_changes',
        { event: 'INSERT', schema: 'public', table: 'device_health', filter: `device_id=eq.${selected}` },
        () => {
          loadDeviceHealth()
        }
      )
      .subscribe()

    return () => {
      supabase.removeChannel(channel)
    }
  }, [selected])

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '300px' }}>
        <RefreshCw className="spin" size={24} style={{ color: 'var(--primary)' }} />
        <span style={{ marginLeft: 10, color: 'var(--text-secondary)' }}>Loading devices...</span>
      </div>
    )
  }

  return (
    <div className="page fade-in">
      {/* Device Selector */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 24, flexWrap: 'wrap', alignItems: 'center' }}>
        {devices.length === 0 ? (
          <span style={{ color: 'var(--text-muted)' }}>No registered devices under management.</span>
        ) : (
          devices.map(d => (
            <button
              key={d}
              onClick={() => setSelected(d)}
              className={`btn btn-sm ${selected === d ? 'btn-primary' : 'btn-secondary'}`}
            >
              {d}
            </button>
          ))
        )}
        <button onClick={loadDeviceHealth} className="btn btn-secondary btn-sm" style={{ marginLeft: 'auto' }}>
          <RefreshCw className={telemetryLoading ? 'spin' : ''} size={14} />Refresh
        </button>
      </div>

      {!snapshot ? (
        <div style={{ padding: 48, textAlign: 'center', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--bg-surface)' }}>
          <span style={{ fontSize: 32, display: 'block', marginBottom: 12 }}>📊</span>
          <span style={{ color: 'var(--text-muted)' }}>
            No health records logged for device <strong>{selected}</strong> yet.
          </span>
          <span style={{ display: 'block', fontSize: 12, color: 'var(--text-muted)', marginTop: 8 }}>
            (Device health updates are broadcasted automatically every 30 seconds)
          </span>
        </div>
      ) : (
        <>
          {/* Snapshot Stats */}
          <div className="stats-grid fade-in fade-in-1" style={{ marginBottom: 24 }}>
            {[
              {
                label: 'CPU Usage',
                value: `${snapshot.cpu_usage ?? '—'}%`,
                color: 'purple',
                icon: <Cpu size={20} color="var(--primary)" />
              },
              {
                label: 'Free Heap',
                value: `${Math.round((snapshot.free_heap || 0) / 1024)} KB`,
                color: 'cyan',
                icon: <Activity size={20} color="var(--secondary)" />
              },
              {
                label: 'RSSI',
                value: `${snapshot.rssi ?? '—'} dBm`,
                color: snapshot.rssi < -80 ? 'red' : 'green',
                icon: (
                  <Wifi
                    size={20}
                    color={snapshot.rssi < -80 ? 'var(--danger)' : 'var(--success)'}
                  />
                )
              },
              {
                label: 'Chip Temperature',
                value: `${snapshot.temperature ?? '—'}°C`,
                color: snapshot.temperature > 70 ? 'red' : 'orange',
                icon: '🌡️'
              },
              {
                label: 'Battery Level',
                value: `${snapshot.battery ?? '—'}%`,
                color: 'green',
                icon: '🔋'
              },
              {
                label: 'Uptime',
                value: snapshot.uptime_sec ? `${Math.round(snapshot.uptime_sec / 3600)} hrs` : '—',
                color: 'cyan',
                icon: '⏱️'
              },
              {
                label: 'Restart Count',
                value: snapshot.restart_count ?? '0',
                color: snapshot.restart_count > 3 ? 'red' : 'purple',
                icon: '🔄'
              },
              {
                label: 'Packet Loss',
                value: `${snapshot.packet_loss ?? '0'}%`,
                color: snapshot.packet_loss > 5 ? 'red' : 'green',
                icon: (
                  <AlertTriangle
                    size={20}
                    color={snapshot.packet_loss > 5 ? 'var(--danger)' : 'var(--success)'}
                  />
                )
              }
            ].map(s => (
              <div key={s.label} className={`stat-card ${s.color}`}>
                <div className={`stat-icon ${s.color}`}>
                  {typeof s.icon === 'string' ? <span style={{ fontSize: 18 }}>{s.icon}</span> : s.icon}
                </div>
                <div className="stat-label">{s.label}</div>
                <div className="stat-value" style={{ fontSize: 22 }}>
                  {s.value}
                </div>
              </div>
            ))}
          </div>

          {/* Telemetry Charts */}
          {history.length > 0 && (
            <>
              <div className="grid-2 fade-in fade-in-2" style={{ marginBottom: 20 }}>
                <div className="card">
                  <div className="section-title mb-16">
                    <Cpu size={16} />CPU Usage — {selected}
                  </div>
                  <ResponsiveContainer width="100%" height={180}>
                    <AreaChart data={history} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="gCpu" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="var(--primary)" stopOpacity={0.4} />
                          <stop offset="95%" stopColor="var(--primary)" stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                      <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="%" />
                      <Tooltip {...TOOLTIP_STYLE} />
                      <Area
                        type="monotone"
                        dataKey="cpu"
                        stroke="var(--primary)"
                        fill="url(#gCpu)"
                        strokeWidth={2}
                        name="CPU %"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>

                <div className="card">
                  <div className="section-title mb-16">
                    <Activity size={16} />Free Heap — {selected}
                  </div>
                  <ResponsiveContainer width="100%" height={180}>
                    <AreaChart data={history} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="gHeap" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="var(--success)" stopOpacity={0.4} />
                          <stop offset="95%" stopColor="var(--success)" stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                      <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="KB" />
                      <Tooltip {...TOOLTIP_STYLE} />
                      <Area
                        type="monotone"
                        dataKey="heap"
                        stroke="var(--success)"
                        fill="url(#gHeap)"
                        strokeWidth={2}
                        name="Heap KB"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="grid-2 fade-in fade-in-3">
                <div className="card">
                  <div className="section-title mb-16">
                    <Wifi size={16} />RSSI — {selected}
                  </div>
                  <ResponsiveContainer width="100%" height={180}>
                    <LineChart data={history} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                      <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                      <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="dBm" />
                      <Tooltip {...TOOLTIP_STYLE} />
                      <Line
                        type="monotone"
                        dataKey="rssi"
                        stroke="var(--secondary)"
                        strokeWidth={2}
                        dot={false}
                        name="RSSI dBm"
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </div>

                <div className="card">
                  <div className="section-title mb-16">🏓 RTT Latency — {selected}</div>
                  <ResponsiveContainer width="100%" height={180}>
                    <BarChart data={history} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                      <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                      <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="ms" />
                      <Tooltip {...TOOLTIP_STYLE} />
                      <Bar dataKey="rtt" fill="var(--warning)" radius={[4, 4, 0, 0]} name="RTT ms" />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </>
          )}
        </>
      )}
    </div>
  )
}
