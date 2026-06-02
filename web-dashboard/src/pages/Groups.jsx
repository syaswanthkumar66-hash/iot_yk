import { useState, useEffect } from 'react'
import { Users, Plus, Trash2, ToggleLeft, ToggleRight, Zap, RefreshCw } from 'lucide-react'
import { supabase } from '../lib/supabase'
import { sendCommand } from '../lib/api'

export default function Groups() {
  const [groups, setGroups] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [newName, setNewName] = useState('')

  const loadGroups = async () => {
    try {
      setLoading(true)
      const { data, error } = await supabase
        .from('device_groups')
        .select('*')
        .order('group_id')
      if (error) throw error

      // Annotate each group with an in-memory 'state' (default 'OFF')
      setGroups((data || []).map(g => ({ ...g, state: 'OFF' })))
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadGroups()

    // Realtime listener for group changes
    const channel = supabase
      .channel('groups-live')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'device_groups' },
        () => { loadGroups() }
      )
      .subscribe()

    return () => {
      supabase.removeChannel(channel)
    }
  }, [])

  const toggleGroup = async (group, turnOn) => {
    const nextAction = turnOn ? 1 : 2 // 1=ON, 2=OFF
    const nextState = turnOn ? 'ON' : 'OFF'
    
    // Optimistic local state update
    setGroups(gs => gs.map(g => g.group_id === group.group_id ? { ...g, state: nextState } : g))

    try {
      // Send command to all group member devices in parallel
      await Promise.all(
        (group.members || []).map(deviceId => sendCommand(deviceId, 1, nextAction)) // Service 1 (RELAY)
      )
    } catch (err) {
      console.error('Error toggling group:', err)
      // Revert state back on error
      setGroups(gs => gs.map(g => g.group_id === group.group_id ? { ...g, state: turnOn ? 'OFF' : 'ON' } : g))
    }
  }

  const addGroup = async () => {
    if (!newName.trim()) return
    const nextGroupId = groups.length ? Math.max(...groups.map(g => g.group_id)) + 1 : 100
    try {
      const { data, error } = await supabase
        .from('device_groups')
        .insert({
          group_id: nextGroupId,
          group_name: newName.trim(),
          members: []
        })
        .select()
        .single()
      if (error) throw error

      setGroups(gs => [...gs, { ...data, state: 'OFF' }])
      setNewName('')
    } catch (err) {
      console.error('Error adding group:', err)
    }
  }

  const removeGroup = async (groupId) => {
    try {
      const { error } = await supabase
        .from('device_groups')
        .delete()
        .eq('group_id', groupId)
      if (error) throw error
      setGroups(gs => gs.filter(g => g.group_id !== groupId))
    } catch (err) {
      console.error('Error deleting group:', err)
    }
  }

  return (
    <div className="page fade-in">
      {/* Create New Group Card */}
      <div className="card mb-24 fade-in fade-in-1">
        <div className="section-title mb-16">
          <Plus size={18} />Create New Group
        </div>
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

      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '200px' }}>
          <RefreshCw className="spin" size={24} style={{ color: 'var(--primary)' }} />
          <span style={{ marginLeft: 10, color: 'var(--text-secondary)' }}>Loading live device groups...</span>
        </div>
      ) : error ? (
        <div className="alert-banner warning">
          <span>Failed to load device groups: {error}</span>
        </div>
      ) : groups.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--bg-surface)' }}>
          <Users size={32} style={{ color: 'var(--text-muted)', marginBottom: 12 }} />
          <div style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>No device groups configured yet.</div>
        </div>
      ) : (
        /* Groups Grid */
        <div className="grid-auto fade-in fade-in-2">
          {groups.map(g => (
            <div key={g.group_id} className="card" style={{ transition: 'var(--transition)' }}>
              <div className="flex-between mb-16">
                <div>
                  <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)', marginBottom: 2 }}>
                    GRP{String(g.group_id).padStart(5, '0')}
                  </div>
                  <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-primary)' }}>{g.group_name}</div>
                </div>
                <button className="btn btn-secondary btn-icon" onClick={() => removeGroup(g.group_id)}>
                  <Trash2 size={14} color="var(--danger)" />
                </button>
              </div>

              <div style={{ marginBottom: 16 }}>
                <div
                  style={{
                    fontSize: 11,
                    fontWeight: 700,
                    letterSpacing: '0.5px',
                    textTransform: 'uppercase',
                    color: 'var(--text-muted)',
                    marginBottom: 8
                  }}
                >
                  Members ({(g.members || []).length})
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {(g.members || []).length > 0 ? (
                    g.members.map(m => (
                      <span
                        key={m}
                        style={{
                          background: 'var(--primary-dim)',
                          color: 'var(--primary)',
                          padding: '3px 10px',
                          borderRadius: 20,
                          fontSize: 12,
                          fontFamily: 'var(--font-mono)',
                          border: '1px solid rgba(124,111,255,0.2)'
                        }}
                      >
                        {m}
                      </span>
                    ))
                  ) : (
                    <span style={{ fontSize: 12, color: 'var(--text-muted)', fontStyle: 'italic' }}>No members yet</span>
                  )}
                </div>
              </div>

              <div style={{ display: 'flex', gap: 8, paddingTop: 14, borderTop: '1px solid var(--border)' }}>
                <button
                  onClick={() => toggleGroup(g, g.state !== 'ON')}
                  disabled={(g.members || []).length === 0}
                  className={`btn btn-sm ${g.state === 'ON' ? 'btn-danger' : 'btn-success'}`}
                  style={{ flex: 1 }}
                >
                  {g.state === 'ON' ? (
                    <>
                      <ToggleRight size={14} />All OFF
                    </>
                  ) : (
                    <>
                      <ToggleLeft size={14} />All ON
                    </>
                  )}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
