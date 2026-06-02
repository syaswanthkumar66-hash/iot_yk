import { useState } from 'react'
import { Zap, Plus, Trash2, ToggleLeft, ToggleRight, CheckCircle, XCircle } from 'lucide-react'

const INIT_RULES = [
  { id: 'r1', name: 'Motion → Light ON',    trigger: 'SN001 · SENSOR_REPORT · motion=true',  target: 'SW001 · RELAY_ON',  enabled: true,  lastRun: '2m ago' },
  { id: 'r2', name: 'Temp>30 → Fan ON',     trigger: 'SN002 · SENSOR_REPORT · temp>30',      target: 'MC001 · MOTOR_START {speed:80}', enabled: true,  lastRun: '15m ago' },
  { id: 'r3', name: 'Temp<25 → Fan OFF',    trigger: 'SN002 · SENSOR_REPORT · temp<25',      target: 'MC001 · MOTOR_STOP', enabled: true,  lastRun: '1h ago' },
  { id: 'r4', name: 'Door Open → Alert',    trigger: 'SN003 · SENSOR_REPORT · open=true',    target: 'GW001 · NOTIFY',    enabled: false, lastRun: 'Never' },
  { id: 'r5', name: 'All Lights Night OFF', trigger: 'SCHEDULE · 23:00 daily',               target: 'GRP100 · GROUP_OFF', enabled: true,  lastRun: '8h ago' },
]

export default function Automation() {
  const [rules, setRules] = useState(INIT_RULES)

  const toggleRule = (id) => setRules(rs => rs.map(r => r.id === id ? { ...r, enabled: !r.enabled } : r))
  const deleteRule = (id) => setRules(rs => rs.filter(r => r.id !== id))

  return (
    <div className="page fade-in">
      <div className="flex-between mb-24">
        <div style={{ fontSize: 14, color: 'var(--text-secondary)' }}>
          <span style={{ color: 'var(--success)', fontWeight: 700 }}>{rules.filter(r => r.enabled).length} active</span>
          &nbsp;/ {rules.length} total rules
        </div>
        <button className="btn btn-primary btn-sm">
          <Plus size={16} />New Rule
        </button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }} className="fade-in fade-in-1">
        {rules.map(r => (
          <div key={r.id} className="card" style={{ opacity: r.enabled ? 1 : 0.55, transition: 'var(--transition)' }}>
            <div className="flex-between">
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                  <div style={{ width: 32, height: 32, borderRadius: 8, background: r.enabled ? 'var(--primary-dim)' : 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                    <Zap size={16} color={r.enabled ? 'var(--primary)' : 'var(--text-muted)'} />
                  </div>
                  <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>{r.name}</div>
                  <span className={`badge ${r.enabled ? 'online' : 'offline'}`} style={{ fontSize: 10 }}>
                    {r.enabled ? 'Active' : 'Disabled'}
                  </span>
                </div>

                <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', background: 'rgba(0,212,255,0.08)', borderRadius: 6, border: '1px solid rgba(0,212,255,0.15)' }}>
                    <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--secondary)', textTransform: 'uppercase' }}>IF</span>
                    <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>{r.trigger}</span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', background: 'var(--success-dim)', borderRadius: 6, border: '1px solid rgba(0,230,118,0.15)' }}>
                    <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--success)', textTransform: 'uppercase' }}>THEN</span>
                    <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>{r.target}</span>
                  </div>
                </div>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginLeft: 16, flexShrink: 0 }}>
                <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{r.lastRun}</span>
                <button onClick={() => toggleRule(r.id)} className="btn btn-secondary btn-icon">
                  {r.enabled ? <ToggleRight size={16} color="var(--success)" /> : <ToggleLeft size={16} />}
                </button>
                <button onClick={() => deleteRule(r.id)} className="btn btn-secondary btn-icon">
                  <Trash2 size={14} color="var(--danger)" />
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {rules.length === 0 && (
        <div className="empty-state">
          <Zap />
          <h3>No automation rules</h3>
          <p>Create your first rule to automate your devices</p>
          <button className="btn btn-primary"><Plus size={16} />Create First Rule</button>
        </div>
      )}
    </div>
  )
}
