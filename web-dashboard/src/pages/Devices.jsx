import { useState } from 'react'
import { Cpu, Wifi, Search, RefreshCw, ToggleLeft, ToggleRight, Settings } from 'lucide-react'

const DEVICES = [
  { id: 'SW001', name: 'Living Room Switch', type: 'switch',  emoji: '🔌', online: true,  state: 'ON',   rssi: -62, heap: 186000, cpu: 3.2, fw: 'v1.2.1', ip: '192.168.1.45' },
  { id: 'SW002', name: 'Bedroom Switch',     type: 'switch',  emoji: '🔌', online: true,  state: 'OFF',  rssi: -70, heap: 180000, cpu: 2.8, fw: 'v1.2.1', ip: '192.168.1.46' },
  { id: 'SW003', name: 'Kitchen Switch',     type: 'switch',  emoji: '🔌', online: false, state: '—',    rssi: -85, heap: 0,      cpu: 0,   fw: 'v1.2.0', ip: '—' },
  { id: 'SN001', name: 'Motion Sensor',      type: 'sensor',  emoji: '📡', online: true,  state: 'IDLE', rssi: -58, heap: 192000, cpu: 1.5, fw: 'v1.2.1', ip: '192.168.1.47' },
  { id: 'SN002', name: 'Temp & Humidity',    type: 'sensor',  emoji: '🌡️', online: true,  state: 'IDLE', rssi: -65, heap: 188000, cpu: 2.1, fw: 'v1.2.1', ip: '192.168.1.48' },
  { id: 'GW001', name: 'Main Gateway',       type: 'gateway', emoji: '🔀', online: true,  state: 'MESH', rssi: -45, heap: 220000, cpu: 8.3, fw: 'v1.2.0', ip: '192.168.1.10' },
  { id: 'MC001', name: 'Fan Motor',          type: 'motor',   emoji: '⚙️', online: true,  state: 'ON',   rssi: -72, heap: 174000, cpu: 4.1, fw: 'v1.1.2', ip: '192.168.1.51' },
  { id: 'SN003', name: 'Door Sensor',        type: 'sensor',  emoji: '🚪', online: false, state: '—',    rssi: -90, heap: 0,      cpu: 0,   fw: 'v1.1.0', ip: '—' },
]

export default function Devices() {
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('all')
  const [states, setStates] = useState(Object.fromEntries(DEVICES.map(d => [d.id, d.state])))

  const toggle = (id) => {
    setStates(s => ({ ...s, [id]: s[id] === 'ON' ? 'OFF' : 'ON' }))
  }

  const filtered = DEVICES.filter(d => {
    const matchType = filter === 'all' || d.type === filter
    const matchSearch = d.name.toLowerCase().includes(search.toLowerCase()) || d.id.toLowerCase().includes(search.toLowerCase())
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
            >{f}</button>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <div style={{ position: 'relative' }}>
            <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input
              className="input"
              style={{ paddingLeft: 32, width: 220 }}
              placeholder="Search devices..."
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <button className="btn btn-secondary btn-sm">
            <RefreshCw size={14} />Refresh
          </button>
        </div>
      </div>

      <div className="grid-auto">
        {filtered.map(d => (
          <div key={d.id} className="device-card fade-in">
            <div className="device-card-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div className="device-icon">{d.emoji}</div>
                <div>
                  <div className="device-id">{d.id}</div>
                  <div className="device-name">{d.name}</div>
                </div>
              </div>
              <span className={`badge ${d.online ? 'online' : 'offline'}`}>
                {d.online ? 'Online' : 'Offline'}
              </span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <span className={`tag ${d.type}`}>{d.type.toUpperCase()}</span>
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' }}>{d.fw}</span>
            </div>

            <div style={{ marginBottom: 14 }}>
              <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4, textTransform: 'uppercase', letterSpacing: '0.5px', fontWeight: 600 }}>IP Address</div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--text-secondary)' }}>{d.ip}</div>
            </div>

            <div className="device-stats">
              <div className="device-stat-item">
                <div className="label">RSSI</div>
                <div className="value" style={{ color: !d.online ? 'var(--text-muted)' : d.rssi < -80 ? 'var(--danger)' : d.rssi < -70 ? 'var(--warning)' : 'var(--success)' }}>
                  {d.online ? `${d.rssi}` : '—'}
                </div>
              </div>
              <div className="device-stat-item">
                <div className="label">Heap</div>
                <div className="value">{d.online ? `${Math.round(d.heap / 1024)}KB` : '—'}</div>
              </div>
              <div className="device-stat-item">
                <div className="label">CPU</div>
                <div className="value">{d.online ? `${d.cpu}%` : '—'}</div>
              </div>
              <div className="device-stat-item">
                <div className="label">State</div>
                <div className="value" style={{ color: states[d.id] === 'ON' ? 'var(--success)' : 'var(--text-muted)' }}>
                  {states[d.id]}
                </div>
              </div>
            </div>

            {d.online && d.type === 'switch' && (
              <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--border)', display: 'flex', gap: 8 }}>
                <button
                  onClick={() => toggle(d.id)}
                  className={`btn btn-sm w-full ${states[d.id] === 'ON' ? 'btn-danger' : 'btn-success'}`}
                >
                  {states[d.id] === 'ON'
                    ? <><ToggleRight size={14} /> Turn OFF</>
                    : <><ToggleLeft size={14} />  Turn ON</>
                  }
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
