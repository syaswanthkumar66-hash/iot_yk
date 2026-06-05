import { useState, useEffect } from 'react'
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts'
import {
  Cpu, Wifi, AlertTriangle, CheckCircle, ArrowUp, ArrowDown, Clock, Zap, Activity, Radio, RefreshCw
} from 'lucide-react'
import { fetchDevices, getBackendUrl } from '../lib/api'
import { supabase } from '../lib/supabase'

const DEVICE_EMOJI = {
  switch: '🔌',
  sensor: '📡',
  gateway: '🔀',
  motor: '⚙️'
}

const WssTester = () => {
  const [status, setStatus] = useState('idle');
  const [log, setLog] = useState('');

  const runTest = () => {
    setStatus('testing');
    setLog('Connecting to WebSocket server...');
    try {
      const wsUrl = getBackendUrl().replace(/^http/, 'ws') + '/ws';
      const ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        setLog(prev => prev + '\n✓ TCP Connection established.');
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === 'CONNECTED') {
            setLog(prev => prev + `\n✓ Received handshake from: ${data.server}`);
            setStatus('success');
            ws.close();
          }
        } catch (e) {
          // Ignore binary/other messages
        }
      };

      ws.onerror = (e) => {
        setLog(prev => prev + '\n❌ WebSocket Error! Is the backend running?');
        setStatus(prev => prev === 'testing' ? 'error' : prev);
      };

      ws.onclose = () => {
        setStatus(prev => {
          if (prev === 'testing') {
            setLog(l => l + '\n❌ Connection closed prematurely.');
            return 'error';
          }
          return prev;
        });
      };
      
      // Timeout
      setTimeout(() => {
        if (ws.readyState === WebSocket.CONNECTING) {
          ws.close();
          setLog(prev => prev + '\n❌ Connection timed out.');
          setStatus('error');
        }
      }, 5000);
    } catch (e) {
      setLog(prev => prev + '\n❌ Failed to initialize WebSocket: ' + e.message);
      setStatus('error');
    }
  };

  return (
    <div className="card fade-in" style={{ marginBottom: 24 }}>
      <div className="flex-between" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div className="section-title" style={{ marginBottom: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Radio size={18} /> Cloud WebSocket Connectivity Test
        </div>
        <button className="btn btn-primary btn-sm" onClick={runTest} disabled={status === 'testing'}>
          {status === 'testing' ? 'Testing...' : 'Run Test'}
        </button>
      </div>
      <div style={{ background: '#0f172a', padding: 16, borderRadius: 8, color: '#10b981', fontFamily: 'monospace', whiteSpace: 'pre-wrap', fontSize: 13, minHeight: 60 }}>
        {log || 'Click "Run Test" to verify if the Cloud WebSocket server is reachable from your browser.'}
      </div>
    </div>
  );
};

// Generate static sample traffic points for activity graph
const genChart = () =>
  Array.from({ length: 12 }, (_, i) => ({
    t: `${i * 5}m`,
    online: Math.floor(5 + Math.random() * 2),
    packets: Math.floor(80 + Math.random() * 40),
  }))

export default function Dashboard() {
  const [devices, setDevices] = useState([])
  const [rulesCount, setRulesCount] = useState(0)
  const [alerts, setAlerts] = useState([])
  const [chart] = useState(genChart)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const loadDashboardData = async () => {
    try {
      setLoading(true)
      // 1. Fetch live devices
      const devData = await fetchDevices()
      setDevices(devData)

      // 2. Fetch automation rules count
      const { count: rCount } = await supabase
        .from('automation_rules')
        .select('*', { count: 'exact', head: true })
      setRulesCount(rCount || 0)

      // 3. Fetch recent audit logs/alerts
      const { data: logs } = await supabase
        .from('audit_logs')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(5)

      if (logs) {
        setAlerts(
          logs.map(log => ({
            id: log.id,
            type: log.result === 'success' ? 'success' : 'warning',
            msg: `${log.device_id || 'SYSTEM'}: ${log.action.replace(/_/g, ' ')}`,
            time: new Date(log.created_at).toLocaleTimeString()
          }))
        )
      }
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadDashboardData()

    // Subscribe to realtime database changes for instant dashboard updates
    const channel = supabase
      .channel('dashboard-realtime')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'devices' },
        () => { loadDashboardData() }
      )
      .on(
        'postgres_changes',
        { event: 'UPDATE', schema: 'public', table: 'device_state' },
        () => { loadDashboardData() }
      )
      .on(
        'postgres_changes',
        { event: 'INSERT', schema: 'public', table: 'audit_logs' },
        (payload) => {
          const log = payload.new
          setAlerts(prev => [
            {
              id: log.id,
              type: log.result === 'success' ? 'success' : 'warning',
              msg: `${log.device_id || 'SYSTEM'}: ${log.action.replace(/_/g, ' ')}`,
              time: new Date(log.created_at).toLocaleTimeString()
            },
            ...prev.slice(0, 4)
          ])
        }
      )
      .subscribe()

    return () => {
      supabase.removeChannel(channel)
    }
  }, [])

  const online = devices.filter(d => d.is_online).length
  const offline = devices.length - online
  const activeDevices = devices.filter(d => d.is_online)
  const avgRssi = activeDevices.length
    ? Math.round(activeDevices.reduce((a, d) => a + (d.rssi || -70), 0) / activeDevices.length)
    : '—'

  return (
    <div className="page fade-in">
      {error && (
        <div className="alert-banner warning mb-24">
          <span>Failed to connect to Render Worker: {error}</span>
        </div>
      )}

      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '300px' }}>
          <RefreshCw className="spin" size={24} style={{ color: 'var(--primary)' }} />
          <span style={{ marginLeft: 10, color: 'var(--text-secondary)' }}>Loading dynamic dashboard metrics...</span>
        </div>
      ) : (
        <>
          {/* ── WSS Tester ── */}
          <WssTester />

          {/* ── Stats ── */}
          <div className="stats-grid fade-in fade-in-1">
            <div className="stat-card purple">
              <div className="stat-icon purple">
                <Cpu size={20} color="var(--primary)" />
              </div>
              <div className="stat-label">Total Devices</div>
              <div className="stat-value">{devices.length}</div>
              <div className="stat-change">Registered smart nodes</div>
            </div>

            <div className="stat-card green">
              <div className="stat-icon green">
                <CheckCircle size={20} color="var(--success)" />
              </div>
              <div className="stat-label">Online</div>
              <div className="stat-value">{online}</div>
              <div className="stat-change up">
                <ArrowUp size={12} />
                {devices.length ? Math.round((online / devices.length) * 100) : 0}% active
              </div>
            </div>

            <div className="stat-card red">
              <div className="stat-icon red">
                <AlertTriangle size={20} color="var(--danger)" />
              </div>
              <div className="stat-label">Offline</div>
              <div className="stat-value">{offline}</div>
              <div className="stat-change down">
                <ArrowDown size={12} />
                Needs attention
              </div>
            </div>

            <div className="stat-card cyan">
              <div className="stat-icon cyan">
                <Wifi size={20} color="var(--secondary)" />
              </div>
              <div className="stat-label">Avg RSSI</div>
              <div className="stat-value">{avgRssi}</div>
              <div className="stat-change">dBm signal strength</div>
            </div>

            <div className="stat-card orange">
              <div className="stat-icon orange">
                <Zap size={20} color="var(--warning)" />
              </div>
              <div className="stat-label">Active Rules</div>
              <div className="stat-value">{rulesCount}</div>
              <div className="stat-change">Rules running in engine</div>
            </div>

            <div className="stat-card pink">
              <div className="stat-icon pink">
                <Radio size={20} color="var(--accent)" />
              </div>
              <div className="stat-label">Telemetry Events</div>
              <div className="stat-value">{alerts.length}</div>
              <div className="stat-change up">
                <ArrowUp size={12} />
                Real-time active
              </div>
            </div>
          </div>

          {/* ── Chart + Alerts ── */}
          <div className="grid-2 fade-in fade-in-2" style={{ marginBottom: 24 }}>
            <div className="card">
              <div className="section-title">
                <Activity size={18} />
                Network Activity
              </div>
              <ResponsiveContainer width="100%" height={200}>
                <AreaChart data={chart} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="gradOnline" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="var(--primary)" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="var(--primary)" stopOpacity={0.02} />
                    </linearGradient>
                    <linearGradient id="gradPkts" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="var(--secondary)" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="var(--secondary)" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                  <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                  <Tooltip
                    contentStyle={{
                      background: 'var(--bg-surface)',
                      border: '1px solid var(--border)',
                      borderRadius: 8
                    }}
                    labelStyle={{ color: 'var(--text-secondary)' }}
                    itemStyle={{ color: 'var(--text-primary)' }}
                  />
                  <Area
                    type="monotone"
                    dataKey="online"
                    stroke="var(--primary)"
                    fill="url(#gradOnline)"
                    strokeWidth={2}
                    name="Online"
                  />
                  <Area
                    type="monotone"
                    dataKey="packets"
                    stroke="var(--secondary)"
                    fill="url(#gradPkts)"
                    strokeWidth={2}
                    name="Packets/min"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>

            <div className="card">
              <div className="section-title">
                <AlertTriangle size={18} />
                Recent System Log Events
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {alerts.length === 0 ? (
                  <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>
                    No recent events logged.
                  </div>
                ) : (
                  alerts.map(a => (
                    <div key={a.id} className={`alert-banner ${a.type}`}>
                      {a.type === 'warning' ? <AlertTriangle size={16} /> : <CheckCircle size={16} />}
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: 13, fontWeight: 600 }}>{a.msg}</div>
                      </div>
                      <div
                        style={{
                          fontSize: 11,
                          opacity: 0.7,
                          display: 'flex',
                          alignItems: 'center',
                          gap: 4
                        }}
                      >
                        <Clock size={11} />
                        {a.time}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>

          {/* ── Device List ── */}
          <div className="card fade-in fade-in-3">
            <div className="flex-between mb-16">
              <div className="section-title" style={{ marginBottom: 0 }}>
                <Cpu size={18} />
                Device Overview
              </div>
            </div>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Device</th>
                    <th>Type</th>
                    <th>Status</th>
                    <th>Signal Strength</th>
                    <th>Firmware</th>
                  </tr>
                </thead>
                <tbody>
                  {devices.map(d => (
                    <tr key={d.device_id}>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <span style={{ fontSize: 20 }}>{DEVICE_EMOJI[d.device_type] || '⚡'}</span>
                          <div>
                            <div style={{ fontWeight: 600, color: 'var(--text-primary)', fontSize: 13 }}>
                              {d.device_name || 'Generic Device'}
                            </div>
                            <div
                              style={{
                                fontFamily: 'var(--font-mono)',
                                fontSize: 11,
                                color: 'var(--text-muted)'
                              }}
                            >
                              {d.device_id}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td>
                        <span className={`tag ${d.device_type}`}>{d.device_type.toUpperCase()}</span>
                      </td>
                      <td>
                        <span className={`badge ${d.is_online ? 'online' : 'offline'}`}>
                          {d.is_online ? 'Online' : 'Offline'}
                        </span>
                      </td>
                      <td
                        style={{
                          fontFamily: 'var(--font-mono)',
                          color:
                            !d.is_online
                              ? 'var(--text-muted)'
                              : d.rssi < -80
                              ? 'var(--danger)'
                              : d.rssi < -70
                              ? 'var(--warning)'
                              : 'var(--success)'
                        }}
                      >
                        {d.is_online && d.rssi ? `${d.rssi} dBm` : '—'}
                      </td>
                      <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--text-muted)' }}>
                        {d.firmware_ver || 'v1.0.0'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
