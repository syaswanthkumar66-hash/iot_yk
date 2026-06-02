import { useState, useEffect } from 'react'
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer
} from 'recharts'
import {
  Cpu, Wifi, AlertTriangle, CheckCircle, ArrowUp,
  ArrowDown, Clock, Zap, Activity, Radio
} from 'lucide-react'

const MOCK_DEVICES = [
  { id: 'SW001', name: 'Living Room Switch', type: 'switch', online: true,  rssi: -62, heap: 186000, cpu: 3.2 },
  { id: 'SW002', name: 'Bedroom Switch',     type: 'switch', online: true,  rssi: -70, heap: 180000, cpu: 2.8 },
  { id: 'SW003', name: 'Kitchen Switch',     type: 'switch', online: false, rssi: -85, heap: 0,      cpu: 0   },
  { id: 'SN001', name: 'Motion Sensor',      type: 'sensor', online: true,  rssi: -58, heap: 192000, cpu: 1.5 },
  { id: 'SN002', name: 'Temp Sensor',        type: 'sensor', online: true,  rssi: -65, heap: 188000, cpu: 2.1 },
  { id: 'GW001', name: 'Main Gateway',       type: 'gateway',online: true,  rssi: -45, heap: 220000, cpu: 8.3 },
  { id: 'MC001', name: 'Fan Motor',          type: 'motor',  online: true,  rssi: -72, heap: 174000, cpu: 4.1 },
  { id: 'SN003', name: 'Door Sensor',        type: 'sensor', online: false, rssi: -90, heap: 0,      cpu: 0   },
]

const genChart = () => Array.from({ length: 12 }, (_, i) => ({
  t: `${i * 5}m`,
  online: Math.floor(5 + Math.random() * 2),
  packets: Math.floor(80 + Math.random() * 40),
}))

const DEVICE_EMOJI = { switch: '🔌', sensor: '📡', gateway: '🔀', motor: '⚙️', display: '🖥️' }

export default function Dashboard() {
  const [chart] = useState(genChart)
  const [tick, setTick]   = useState(0)
  const [alerts, setAlerts] = useState([
    { id: 1, type: 'warning', msg: 'SW003 offline for 15 minutes', time: '5m ago' },
    { id: 2, type: 'warning', msg: 'SN003 RSSI below -85 dBm',    time: '12m ago' },
    { id: 3, type: 'success', msg: 'OTA complete on GW001 v1.2.1', time: '1h ago' },
  ])

  useEffect(() => {
    const t = setInterval(() => setTick(n => n + 1), 5000)
    return () => clearInterval(t)
  }, [])

  const online  = MOCK_DEVICES.filter(d => d.online).length
  const offline = MOCK_DEVICES.length - online
  const avgRssi = Math.round(MOCK_DEVICES.filter(d => d.online).reduce((a, d) => a + d.rssi, 0) / online)

  return (
    <div className="page fade-in">
      {/* ── Stats ── */}
      <div className="stats-grid fade-in fade-in-1">
        <div className="stat-card purple">
          <div className="stat-icon purple"><Cpu size={20} color="var(--primary)" /></div>
          <div className="stat-label">Total Devices</div>
          <div className="stat-value">{MOCK_DEVICES.length}</div>
          <div className="stat-change">All registered devices</div>
        </div>

        <div className="stat-card green">
          <div className="stat-icon green"><CheckCircle size={20} color="var(--success)" /></div>
          <div className="stat-label">Online</div>
          <div className="stat-value">{online}</div>
          <div className="stat-change up"><ArrowUp size={12} />{Math.round(online / MOCK_DEVICES.length * 100)}% uptime</div>
        </div>

        <div className="stat-card red">
          <div className="stat-icon red"><AlertTriangle size={20} color="var(--danger)" /></div>
          <div className="stat-label">Offline</div>
          <div className="stat-value">{offline}</div>
          <div className="stat-change down"><ArrowDown size={12} />Need attention</div>
        </div>

        <div className="stat-card cyan">
          <div className="stat-icon cyan"><Wifi size={20} color="var(--secondary)" /></div>
          <div className="stat-label">Avg RSSI</div>
          <div className="stat-value">{avgRssi}</div>
          <div className="stat-change">dBm signal strength</div>
        </div>

        <div className="stat-card orange">
          <div className="stat-icon orange"><Zap size={20} color="var(--warning)" /></div>
          <div className="stat-label">Active Rules</div>
          <div className="stat-value">5</div>
          <div className="stat-change">Automation rules running</div>
        </div>

        <div className="stat-card pink">
          <div className="stat-icon pink"><Radio size={20} color="var(--accent)" /></div>
          <div className="stat-label">Packets / min</div>
          <div className="stat-value">124</div>
          <div className="stat-change up"><ArrowUp size={12} />Normal traffic</div>
        </div>
      </div>

      {/* ── Chart + Alerts ── */}
      <div className="grid-2 fade-in fade-in-2" style={{ marginBottom: 24 }}>
        <div className="card">
          <div className="section-title"><Activity size={18} />Network Activity</div>
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={chart} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <defs>
                <linearGradient id="gradOnline" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="var(--primary)"   stopOpacity={0.35} />
                  <stop offset="95%" stopColor="var(--primary)"  stopOpacity={0.02} />
                </linearGradient>
                <linearGradient id="gradPkts" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="var(--secondary)" stopOpacity={0.35} />
                  <stop offset="95%" stopColor="var(--secondary)"stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              <Tooltip
                contentStyle={{ background: 'var(--bg-surface)', border: '1px solid var(--border)', borderRadius: 8 }}
                labelStyle={{ color: 'var(--text-secondary)' }}
                itemStyle={{ color: 'var(--text-primary)' }}
              />
              <Area type="monotone" dataKey="online"  stroke="var(--primary)"   fill="url(#gradOnline)" strokeWidth={2} name="Online" />
              <Area type="monotone" dataKey="packets" stroke="var(--secondary)" fill="url(#gradPkts)"   strokeWidth={2} name="Packets/min" />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        <div className="card">
          <div className="section-title"><AlertTriangle size={18} />Recent Alerts</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {alerts.map(a => (
              <div key={a.id} className={`alert-banner ${a.type}`}>
                {a.type === 'warning'
                  ? <AlertTriangle size={16} />
                  : <CheckCircle size={16} />
                }
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{a.msg}</div>
                </div>
                <div style={{ fontSize: 11, opacity: 0.7, display: 'flex', alignItems: 'center', gap: 4 }}>
                  <Clock size={11} />{a.time}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Device List ── */}
      <div className="card fade-in fade-in-3">
        <div className="flex-between mb-16">
          <div className="section-title" style={{ marginBottom: 0 }}><Cpu size={18} />Device Overview</div>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Device</th>
                <th>Type</th>
                <th>Status</th>
                <th>RSSI</th>
                <th>Free Heap</th>
                <th>CPU</th>
                <th>Firmware</th>
              </tr>
            </thead>
            <tbody>
              {MOCK_DEVICES.map(d => (
                <tr key={d.id}>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <span style={{ fontSize: 20 }}>{DEVICE_EMOJI[d.type]}</span>
                      <div>
                        <div style={{ fontWeight: 600, color: 'var(--text-primary)', fontSize: 13 }}>{d.name}</div>
                        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' }}>{d.id}</div>
                      </div>
                    </div>
                  </td>
                  <td><span className={`tag ${d.type}`}>{d.type.toUpperCase()}</span></td>
                  <td>
                    <span className={`badge ${d.online ? 'online' : 'offline'}`}>
                      {d.online ? 'Online' : 'Offline'}
                    </span>
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', color: d.rssi < -80 ? 'var(--danger)' : d.rssi < -70 ? 'var(--warning)' : 'var(--success)' }}>
                    {d.online ? `${d.rssi} dBm` : '—'}
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: 13 }}>
                    {d.online ? `${Math.round(d.heap / 1024)} KB` : '—'}
                  </td>
                  <td>
                    {d.online ? (
                      <div>
                        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13 }}>{d.cpu}%</div>
                        <div className="progress-bar" style={{ width: 80, marginTop: 4 }}>
                          <div className="progress-fill" style={{ width: `${d.cpu * 10}%`, background: d.cpu > 70 ? 'var(--danger)' : 'linear-gradient(90deg, var(--primary), var(--secondary))' }} />
                        </div>
                      </div>
                    ) : '—'}
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--text-muted)' }}>v1.2.1</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
