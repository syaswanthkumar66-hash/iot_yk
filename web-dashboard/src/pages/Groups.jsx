import { useState } from 'react'
import { Users, Plus, Trash2, ToggleLeft, ToggleRight, Zap } from 'lucide-react'

const INIT_GROUPS = [
  { id: 100, name: 'Living Room Lights', members: ['SW001', 'SW002'], state: 'ON' },
  { id: 101, name: 'Kitchen Devices',    members: ['SW003'],          state: 'OFF' },
  { id: 102, name: 'All Switches',       members: ['SW001', 'SW002', 'SW003'], state: 'OFF' },
]

export default function Groups() {
  const [groups, setGroups] = useState(INIT_GROUPS)
  const [newName, setNewName] = useState('')

  const toggle = (id) => setGroups(gs => gs.map(g => g.id === id ? { ...g, state: g.state === 'ON' ? 'OFF' : 'ON' } : g))
  const remove = (id) => setGroups(gs => gs.filter(g => g.id !== id))
  const addGroup = () => {
    if (!newName.trim()) return
    setGroups(gs => [...gs, { id: Math.max(...gs.map(g => g.id)) + 1, name: newName.trim(), members: [], state: 'OFF' }])
    setNewName('')
  }

  return (
    <div className="page fade-in">
      {/* New group */}
      <div className="card mb-24 fade-in fade-in-1">
        <div className="section-title mb-16"><Plus size={18} />Create New Group</div>
        <div style={{ display: 'flex', gap: 12 }}>
          <input
            className="input"
            placeholder="Group name (e.g. Bedroom Lights)..."
            value={newName}
            onChange={e => setNewName(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && addGroup()}
          />
          <button className="btn btn-primary" onClick={addGroup}>
            <Plus size={16} />Create
          </button>
        </div>
      </div>

      {/* Groups */}
      <div className="grid-auto fade-in fade-in-2">
        {groups.map(g => (
          <div key={g.id} className="card" style={{ transition: 'var(--transition)' }}>
            <div className="flex-between mb-16">
              <div>
                <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)', marginBottom: 2 }}>GRP{String(g.id).padStart(5, '0')}</div>
                <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-primary)' }}>{g.name}</div>
              </div>
              <button className="btn btn-secondary btn-icon" onClick={() => remove(g.id)}>
                <Trash2 size={14} color="var(--danger)" />
              </button>
            </div>

            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.5px', textTransform: 'uppercase', color: 'var(--text-muted)', marginBottom: 8 }}>Members ({g.members.length})</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {g.members.length > 0
                  ? g.members.map(m => (
                    <span key={m} style={{ background: 'var(--primary-dim)', color: 'var(--primary)', padding: '3px 10px', borderRadius: 20, fontSize: 12, fontFamily: 'var(--font-mono)', border: '1px solid rgba(124,111,255,0.2)' }}>{m}</span>
                  ))
                  : <span style={{ fontSize: 12, color: 'var(--text-muted)', fontStyle: 'italic' }}>No members yet</span>
                }
              </div>
            </div>

            <div style={{ display: 'flex', gap: 8, paddingTop: 14, borderTop: '1px solid var(--border)' }}>
              <button
                onClick={() => toggle(g.id)}
                className={`btn btn-sm ${g.state === 'ON' ? 'btn-danger' : 'btn-success'}`}
                style={{ flex: 1 }}
              >
                {g.state === 'ON' ? <><ToggleRight size={14} />All OFF</> : <><ToggleLeft size={14} />All ON</>}
              </button>
              <button className="btn btn-secondary btn-sm" style={{ flex: 1 }}>
                <Zap size={14} />Toggle
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
