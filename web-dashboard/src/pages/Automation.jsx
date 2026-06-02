import { useState, useEffect } from 'react'
import { Zap, Plus, Trash2, ToggleLeft, ToggleRight, RefreshCw, AlertTriangle } from 'lucide-react'
import { supabase } from '../lib/supabase'

export default function Automation() {
  const [rules, setRules] = useState([])
  const [devices, setDevices] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [showForm, setShowForm] = useState(false)

  // Form states
  const [ruleName, setRuleName] = useState('')
  const [triggerDevice, setTriggerDevice] = useState('')
  const [triggerService, setTriggerService] = useState('2') // Default 2 (SENSOR)
  const [triggerAction, setTriggerAction] = useState('1')   // Default 1 (REPORT)
  const [targetDevice, setTargetDevice] = useState('')
  const [targetService, setTargetService] = useState('1')   // Default 1 (RELAY)
  const [targetAction, setTargetAction] = useState('1')     // Default 1 (ON)

  const loadAutomationData = async () => {
    try {
      setLoading(true)
      // 1. Fetch automation rules
      const { data: rData, error: rErr } = await supabase
        .from('automation_rules')
        .select('*')
        .order('created_at', { ascending: false })
      if (rErr) throw rErr
      setRules(rData || [])

      // 2. Fetch registered devices for selections
      const { data: dData, error: dErr } = await supabase
        .from('devices')
        .select('device_id,device_name')
        .order('device_id')
      if (dErr) throw dErr
      
      const devs = dData || []
      setDevices(devs)
      if (devs.length > 0) {
        setTriggerDevice(devs[0].device_id)
        setTargetDevice(devs[0].device_id)
      }
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAutomationData()

    // Realtime listener for rules changes
    const channel = supabase
      .channel('automation-live')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'automation_rules' },
        () => { loadAutomationData() }
      )
      .subscribe()

    return () => {
      supabase.removeChannel(channel)
    }
  }, [])

  const toggleRule = async (ruleId, currentEnabled) => {
    // Optimistic local state update
    setRules(rs =>
      rs.map(r => (r.rule_id === ruleId ? { ...r, is_enabled: !currentEnabled } : r))
    )

    try {
      const { error } = await supabase
        .from('automation_rules')
        .update({ is_enabled: !currentEnabled })
        .eq('rule_id', ruleId)
      if (error) throw error
    } catch (err) {
      console.error('Error toggling rule:', err)
      // Revert state back on error
      setRules(rs =>
        rs.map(r => (r.rule_id === ruleId ? { ...r, is_enabled: currentEnabled } : r))
      )
    }
  }

  const deleteRule = async (ruleId) => {
    try {
      const { error } = await supabase
        .from('automation_rules')
        .delete()
        .eq('rule_id', ruleId)
      if (error) throw error
      setRules(rs => rs.filter(r => r.rule_id !== ruleId))
    } catch (err) {
      console.error('Error deleting rule:', err)
    }
  }

  const addRule = async (e) => {
    e.preventDefault()
    if (!ruleName.trim()) return

    try {
      const { error } = await supabase
        .from('automation_rules')
        .insert({
          rule_name: ruleName.trim(),
          trigger_device: triggerDevice,
          trigger_service: parseInt(triggerService),
          trigger_action: parseInt(triggerAction),
          target_device: targetDevice,
          target_service: parseInt(targetService),
          target_action: parseInt(targetAction),
          is_enabled: true
        })

      if (error) throw error

      setRuleName('')
      setShowForm(false)
      loadAutomationData()
    } catch (err) {
      console.error('Error adding rule:', err)
    }
  }

  const getServiceName = (id) => {
    if (id === 1) return 'RELAY'
    if (id === 2) return 'SENSOR'
    if (id === 3) return 'MOTOR'
    return 'SERVICE'
  }

  const getActionName = (serviceId, actionId) => {
    if (serviceId === 1) {
      if (actionId === 1) return 'ON'
      if (actionId === 2) return 'OFF'
      if (actionId === 3) return 'TOGGLE'
    }
    if (serviceId === 2) {
      if (actionId === 1) return 'REPORT'
      if (actionId === 2) return 'ALERT'
    }
    if (serviceId === 3) {
      if (actionId === 1) return 'START'
      if (actionId === 2) return 'STOP'
    }
    return `ACTION_${actionId}`
  }

  return (
    <div className="page fade-in">
      <div className="flex-between mb-24">
        <div style={{ fontSize: 14, color: 'var(--text-secondary)' }}>
          <span style={{ color: 'var(--success)', fontWeight: 700 }}>
            {rules.filter(r => r.is_enabled).length} active
          </span>
          &nbsp;/ {rules.length} total rules
        </div>
        <button className="btn btn-primary btn-sm" onClick={() => setShowForm(!showForm)}>
          <Plus size={16} />
          {showForm ? 'Close Form' : 'New Rule'}
        </button>
      </div>

      {/* New Rule Creation Form Card */}
      {showForm && (
        <form onSubmit={addRule} className="card mb-24 fade-in" style={{ padding: '24px 28px' }}>
          <div className="section-title mb-16">
            <Zap size={18} />Configure Automation Trigger Rule
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginBottom: 20 }}>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)', display: 'block', marginBottom: 6 }}>
                Rule Description Name
              </label>
              <input
                className="input"
                placeholder="e.g. Motion ➔ Light ON..."
                value={ruleName}
                onChange={e => setRuleName(e.target.value)}
                required
              />
            </div>

            <div className="grid-2">
              {/* Trigger Block (IF) */}
              <div style={{ padding: 16, background: 'rgba(0,212,255,0.03)', border: '1px solid rgba(0,212,255,0.1)', borderRadius: 8 }}>
                <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--secondary)', textTransform: 'uppercase', marginBottom: 12 }}>
                  IF (Trigger Condition)
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  <div>
                    <label style={{ fontSize: 11, color: 'var(--text-secondary)' }}>Select Trigger Device</label>
                    <select className="input" value={triggerDevice} onChange={e => setTriggerDevice(e.target.value)}>
                      {devices.map(d => (
                        <option key={d.device_id} value={d.device_id}>
                          {d.device_id} - {d.device_name || 'Generic Device'}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label style={{ fontSize: 11, color: 'var(--text-secondary)' }}>Trigger Service</label>
                    <select className="input" value={triggerService} onChange={e => setTriggerService(e.target.value)}>
                      <option value="2">SENSOR (0x02)</option>
                      <option value="1">RELAY (0x01)</option>
                      <option value="3">MOTOR (0x03)</option>
                    </select>
                  </div>
                  <div>
                    <label style={{ fontSize: 11, color: 'var(--text-secondary)' }}>Trigger Action</label>
                    <select className="input" value={triggerAction} onChange={e => setTriggerAction(e.target.value)}>
                      <option value="1">REPORT (0x01)</option>
                      <option value="2">ALERT (0x02)</option>
                    </select>
                  </div>
                </div>
              </div>

              {/* Target Block (THEN) */}
              <div style={{ padding: 16, background: 'rgba(0,230,118,0.03)', border: '1px solid rgba(0,230,118,0.1)', borderRadius: 8 }}>
                <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--success)', textTransform: 'uppercase', marginBottom: 12 }}>
                  THEN (Execute Action)
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  <div>
                    <label style={{ fontSize: 11, color: 'var(--text-secondary)' }}>Select Target Device</label>
                    <select className="input" value={targetDevice} onChange={e => setTargetDevice(e.target.value)}>
                      {devices.map(d => (
                        <option key={d.device_id} value={d.device_id}>
                          {d.device_id} - {d.device_name || 'Generic Device'}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label style={{ fontSize: 11, color: 'var(--text-secondary)' }}>Target Service</label>
                    <select className="input" value={targetService} onChange={e => setTargetService(e.target.value)}>
                      <option value="1">RELAY (0x01)</option>
                      <option value="3">MOTOR (0x03)</option>
                    </select>
                  </div>
                  <div>
                    <label style={{ fontSize: 11, color: 'var(--text-secondary)' }}>Target Action</label>
                    <select className="input" value={targetAction} onChange={e => setTargetAction(e.target.value)}>
                      <option value="1">ON (0x01)</option>
                      <option value="2">OFF (0x02)</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setShowForm(false)}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary btn-sm">
              Create Rule
            </button>
          </div>
        </form>
      )}

      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '200px' }}>
          <RefreshCw className="spin" size={24} style={{ color: 'var(--primary)' }} />
          <span style={{ marginLeft: 10, color: 'var(--text-secondary)' }}>Loading rules from database...</span>
        </div>
      ) : error ? (
        <div className="alert-banner warning">
          <span>Failed to load automation engine: {error}</span>
        </div>
      ) : rules.length === 0 ? (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '200px', flexDirection: 'column' }}>
          <Zap size={32} style={{ color: 'var(--text-muted)', marginBottom: 12 }} />
          <span style={{ color: 'var(--text-muted)', marginBottom: 12 }}>No automation triggers registered.</span>
          <button className="btn btn-primary btn-sm" onClick={() => setShowForm(true)}>
            <Plus size={14} /> Create First Rule
          </button>
        </div>
      ) : (
        /* Rules List */
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }} className="fade-in fade-in-1">
          {rules.map(r => (
            <div key={r.rule_id} className="card" style={{ opacity: r.is_enabled ? 1 : 0.55, transition: 'var(--transition)' }}>
              <div className="flex-between">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                    <div
                      style={{
                        width: 32,
                        height: 32,
                        borderRadius: 8,
                        background: r.is_enabled ? 'var(--primary-dim)' : 'rgba(255,255,255,0.05)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        flexShrink: 0
                      }}
                    >
                      <Zap size={16} color={r.is_enabled ? 'var(--primary)' : 'var(--text-muted)'} />
                    </div>
                    <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>{r.rule_name}</div>
                    <span className={`badge ${r.is_enabled ? 'online' : 'offline'}`} style={{ fontSize: 10 }}>
                      {r.is_enabled ? 'Active' : 'Disabled'}
                    </span>
                  </div>

                  <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        padding: '6px 12px',
                        background: 'rgba(0,212,255,0.08)',
                        borderRadius: 6,
                        border: '1px solid rgba(0,212,255,0.15)'
                      }}
                    >
                      <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--secondary)', textTransform: 'uppercase' }}>
                        IF
                      </span>
                      <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                        {r.trigger_device} · {getServiceName(r.trigger_service)} · {getActionName(r.trigger_service, r.trigger_action)}
                      </span>
                    </div>
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        padding: '6px 12px',
                        background: 'var(--success-dim)',
                        borderRadius: 6,
                        border: '1px solid rgba(0,230,118,0.15)'
                      }}
                    >
                      <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--success)', textTransform: 'uppercase' }}>
                        THEN
                      </span>
                      <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                        {r.target_device} · {getServiceName(r.target_service)} · {getActionName(r.target_service, r.target_action)}
                      </span>
                    </div>
                  </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginLeft: 16, flexShrink: 0 }}>
                  <button onClick={() => toggleRule(r.rule_id, r.is_enabled)} className="btn btn-secondary btn-icon">
                    {r.is_enabled ? <ToggleRight size={16} color="var(--success)" /> : <ToggleLeft size={16} />}
                  </button>
                  <button onClick={() => deleteRule(r.rule_id)} className="btn btn-secondary btn-icon">
                    <Trash2 size={14} color="var(--danger)" />
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
