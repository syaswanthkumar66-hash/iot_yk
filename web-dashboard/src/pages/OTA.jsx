import { useState } from 'react'
import { Upload, CheckCircle, AlertTriangle, Clock, RefreshCw, Plus } from 'lucide-react'

const INIT_JOBS = [
  { id: 'job_001', device: 'GW001', name: 'Main Gateway',   ver: 'v1.2.0', status: 'complete',     progress: 100, chunks: '68/68', time: '2h ago' },
  { id: 'job_002', device: 'SW001', name: 'LR Switch',      ver: 'v1.2.1', status: 'in_progress',  progress: 62,  chunks: '42/68', time: 'Now' },
  { id: 'job_003', device: 'SN002', name: 'Temp Sensor',    ver: 'v1.2.1', status: 'pending',      progress: 0,   chunks: '0/58',  time: 'Queued' },
  { id: 'job_004', device: 'SW003', name: 'Kitchen Switch', ver: 'v1.2.1', status: 'failed',       progress: 33,  chunks: '22/68', time: '4h ago' },
]

const STATUS_MAP = {
  complete:    { label: 'Complete',     badgeClass: 'online',  icon: <CheckCircle size={14} /> },
  in_progress: { label: 'In Progress',  badgeClass: 'idle',    icon: <RefreshCw size={14}   style={{ animation: 'spin 1.5s linear infinite' }} /> },
  pending:     { label: 'Queued',       badgeClass: 'blue',    icon: <Clock size={14} /> },
  failed:      { label: 'Failed',       badgeClass: 'offline', icon: <AlertTriangle size={14} /> },
}

export default function OTA() {
  const [jobs, setJobs] = useState(INIT_JOBS)
  const [dragOver, setDragOver] = useState(false)

  const retry = (id) => setJobs(js => js.map(j => j.id === id ? { ...j, status: 'pending', progress: 0, chunks: `0/${j.chunks.split('/')[1]}` } : j))

  return (
    <div className="page fade-in">
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>

      {/* Upload zone */}
      <div
        className="card fade-in fade-in-1"
        onDragOver={e => { e.preventDefault(); setDragOver(true) }}
        onDragLeave={() => setDragOver(false)}
        onDrop={e => { e.preventDefault(); setDragOver(false) }}
        style={{
          border: `2px dashed ${dragOver ? 'var(--primary)' : 'var(--border)'}`,
          background: dragOver ? 'var(--primary-dim)' : 'var(--bg-card)',
          textAlign: 'center',
          padding: '40px 20px',
          marginBottom: 28,
          transition: 'var(--transition)',
          cursor: 'pointer',
        }}
        onClick={() => document.getElementById('fw-upload').click()}
      >
        <input id="fw-upload" type="file" accept=".bin" style={{ display: 'none' }} />
        <Upload size={36} color="var(--primary)" style={{ margin: '0 auto 12px' }} />
        <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 6 }}>
          Drop firmware .bin here or click to browse
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>
          Supported: ESP32 YKP signed firmware (.bin) — max 2 MB
        </div>
        <button className="btn btn-primary" style={{ marginTop: 16 }} onClick={e => { e.stopPropagation() }}>
          <Plus size={16} />New OTA Job
        </button>
      </div>

      {/* Jobs */}
      <div className="section-title mb-16" style={{ marginBottom: 16 }}><Upload size={18} />OTA Jobs</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }} className="fade-in fade-in-2">
        {jobs.map(j => {
          const s = STATUS_MAP[j.status]
          return (
            <div key={j.id} className="card">
              <div className="flex-between" style={{ marginBottom: 14 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                  <div style={{ width: 44, height: 44, borderRadius: 12, background: 'var(--bg-input)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 22 }}>
                    🔌
                  </div>
                  <div>
                    <div style={{ fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>{j.name}</div>
                    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' }}>{j.device} · {j.ver}</div>
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{j.time}</span>
                  <span className={`badge ${s.badgeClass}`} style={{ display: 'flex', gap: 5 }}>{s.icon}{s.label}</span>
                  {j.status === 'failed' && (
                    <button className="btn btn-secondary btn-sm" onClick={() => retry(j.id)}>
                      <RefreshCw size={13} />Retry
                    </button>
                  )}
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--text-muted)', marginBottom: 6 }}>
                <span>Progress</span>
                <span style={{ fontFamily: 'var(--font-mono)' }}>{j.chunks} chunks · {j.progress}%</span>
              </div>
              <div className="progress-bar">
                <div
                  className={j.status === 'in_progress' ? 'progress-fill' : ''}
                  style={{
                    width: `${j.progress}%`,
                    height: '100%',
                    borderRadius: 3,
                    background: j.status === 'complete' ? 'var(--success)'
                              : j.status === 'failed'   ? 'var(--danger)'
                              : 'linear-gradient(90deg, var(--primary), var(--secondary))',
                    transition: 'width 0.5s ease',
                  }}
                />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
