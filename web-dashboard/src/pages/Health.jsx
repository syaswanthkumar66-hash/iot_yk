import { useState } from 'react'
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer,
  BarChart, Bar, LineChart, Line
} from 'recharts'
import { Activity, Cpu, Wifi, AlertTriangle, RefreshCw } from 'lucide-react'

const genHealth = (baseHeap = 180, baseCpu = 3) => Array.from({ length: 20 }, (_, i) => ({
  t: `${i * 1.5}m`,
  heap:  Math.round(baseHeap + (Math.random() - 0.3) * 20),
  cpu:   +(baseCpu + (Math.random() - 0.4) * 2).toFixed(1),
  rssi:  Math.round(-62 + (Math.random() - 0.5) * 10),
  rtt:   Math.round(40 + Math.random() * 30),
}))

const DEVICES = ['SW001', 'SW002', 'SN001', 'GW001', 'MC001']
const SNAPSHOTS = {
  SW001: { cpu: 3.2, heap: 186, rssi: -62, temp: 42.1, battery: 100, uptime: '5d 14h', restarts: 2, loss: 0.02 },
  SW002: { cpu: 2.8, heap: 180, rssi: -70, temp: 40.5, battery: 100, uptime: '3d 2h',  restarts: 1, loss: 0.01 },
  SN001: { cpu: 1.5, heap: 192, rssi: -58, temp: 39.8, battery: 85,  uptime: '7d 22h', restarts: 0, loss: 0.00 },
  GW001: { cpu: 8.3, heap: 220, rssi: -45, temp: 47.2, battery: 100, uptime: '12d 3h', restarts: 3, loss: 0.05 },
  MC001: { cpu: 4.1, heap: 174, rssi: -72, temp: 44.3, battery: 100, uptime: '2d 8h',  restarts: 1, loss: 0.03 },
}

const TOOLTIP_STYLE = {
  contentStyle: { background: 'var(--bg-surface)', border: '1px solid var(--border)', borderRadius: 8 },
  labelStyle: { color: 'var(--text-secondary)' },
  itemStyle:  { color: 'var(--text-primary)' },
}

export default function Health() {
  const [selected, setSelected] = useState('SW001')
  const [data] = useState(genHealth())
  const snap = SNAPSHOTS[selected]

  return (
    <div className="page fade-in">
      {/* Device selector */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 24, flexWrap: 'wrap' }}>
        {DEVICES.map(d => (
          <button
            key={d}
            onClick={() => setSelected(d)}
            className={`btn btn-sm ${selected === d ? 'btn-primary' : 'btn-secondary'}`}
          >{d}</button>
        ))}
        <button className="btn btn-secondary btn-sm" style={{ marginLeft: 'auto' }}>
          <RefreshCw size={14} />Refresh
        </button>
      </div>

      {/* Snapshot stats */}
      <div className="stats-grid fade-in fade-in-1" style={{ marginBottom: 24 }}>
        {[
          { label: 'CPU Usage',   value: `${snap.cpu}%`,       color: 'purple', icon: <Cpu size={20} color="var(--primary)" /> },
          { label: 'Free Heap',   value: `${snap.heap} KB`,    color: 'cyan',   icon: <Activity size={20} color="var(--secondary)" /> },
          { label: 'RSSI',        value: `${snap.rssi} dBm`,   color: snap.rssi < -80 ? 'red' : 'green', icon: <Wifi size={20} color={snap.rssi < -80 ? 'var(--danger)' : 'var(--success)'} /> },
          { label: 'Temperature', value: `${snap.temp}°C`,     color: snap.temp > 70 ? 'red' : 'orange', icon: '🌡️' },
          { label: 'Battery',     value: `${snap.battery}%`,   color: 'green',  icon: '🔋' },
          { label: 'Uptime',      value: snap.uptime,          color: 'cyan',   icon: '⏱️' },
          { label: 'Restarts',    value: snap.restarts,        color: snap.restarts > 3 ? 'red' : 'purple', icon: '🔄' },
          { label: 'Packet Loss', value: `${snap.loss}%`,      color: snap.loss > 5 ? 'red' : 'green', icon: <AlertTriangle size={20} color={snap.loss > 5 ? 'var(--danger)' : 'var(--success)'} /> },
        ].map(s => (
          <div key={s.label} className={`stat-card ${s.color}`}>
            <div className={`stat-icon ${s.color}`}>
              {typeof s.icon === 'string' ? <span style={{ fontSize: 18 }}>{s.icon}</span> : s.icon}
            </div>
            <div className="stat-label">{s.label}</div>
            <div className="stat-value" style={{ fontSize: 22 }}>{s.value}</div>
          </div>
        ))}
      </div>

      {/* Charts */}
      <div className="grid-2 fade-in fade-in-2" style={{ marginBottom: 20 }}>
        <div className="card">
          <div className="section-title mb-16"><Cpu size={16} />CPU Usage — {selected}</div>
          <ResponsiveContainer width="100%" height={180}>
            <AreaChart data={data} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <defs>
                <linearGradient id="gCpu" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%"  stopColor="var(--primary)" stopOpacity={0.4} />
                  <stop offset="95%" stopColor="var(--primary)" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="%" />
              <Tooltip {...TOOLTIP_STYLE} />
              <Area type="monotone" dataKey="cpu" stroke="var(--primary)" fill="url(#gCpu)" strokeWidth={2} name="CPU %" />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        <div className="card">
          <div className="section-title mb-16"><Activity size={16} />Free Heap — {selected}</div>
          <ResponsiveContainer width="100%" height={180}>
            <AreaChart data={data} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <defs>
                <linearGradient id="gHeap" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%"  stopColor="var(--success)" stopOpacity={0.4} />
                  <stop offset="95%" stopColor="var(--success)" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="KB" />
              <Tooltip {...TOOLTIP_STYLE} />
              <Area type="monotone" dataKey="heap" stroke="var(--success)" fill="url(#gHeap)" strokeWidth={2} name="Heap KB" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="grid-2 fade-in fade-in-3">
        <div className="card">
          <div className="section-title mb-16"><Wifi size={16} />RSSI — {selected}</div>
          <ResponsiveContainer width="100%" height={180}>
            <LineChart data={data} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="dBm" />
              <Tooltip {...TOOLTIP_STYLE} />
              <Line type="monotone" dataKey="rssi" stroke="var(--secondary)" strokeWidth={2} dot={false} name="RSSI dBm" />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <div className="card">
          <div className="section-title mb-16">🏓 RTT Latency — {selected}</div>
          <ResponsiveContainer width="100%" height={180}>
            <BarChart data={data.slice(-10)} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
              <XAxis dataKey="t" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} unit="ms" />
              <Tooltip {...TOOLTIP_STYLE} />
              <Bar dataKey="rtt" fill="var(--warning)" radius={[4, 4, 0, 0]} name="RTT ms" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  )
}
