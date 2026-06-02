import { useState } from 'react'
import { Download, Shield, CheckCircle, Info, Copy, Check,
         ChevronDown, ChevronUp, ExternalLink, Terminal } from 'lucide-react'
import { getWebSocketUrl } from '../lib/api'

/* ── Firmware manifest ── */
const FIRMWARE = [
  {
    id: 'switch',
    emoji: '🔌',
    name: 'YKP Switch Firmware',
    type: 'switch',
    version: 'v1.2.1',
    date: '2026-06-01',
    size: '248 KB',
    file: 'ykp_switch_v1.2.1.bin',
    sha256: 'a3f8c1d94e2b7065f19a4c83d6e0f2b817a5c9e3d1f0478b2c6e9a4f183d0e25',
    targets: ['ESP32', 'ESP32-S2', 'ESP32-S3'],
    desc: 'Official relay/switch firmware. Controls GPIO relay output, reports state changes, supports ON/OFF/TOGGLE commands and QoS 2 guaranteed delivery.',
    features: [
      'Relay ON / OFF / TOGGLE control',
      'Physical button debounce support',
      'QoS 2 guaranteed delivery',
      'AES-256-GCM encryption',
      'Automatic cloud + local UDP',
      'OTA self-update support',
      'Health report every 30 seconds',
    ],
    changelog: [
      { ver: 'v1.2.1', date: '2026-06-01', notes: ['Fixed replay window off-by-one bug', 'Improved WiFi reconnect backoff'] },
      { ver: 'v1.2.0', date: '2026-05-15', notes: ['Added key rotation every 1000 packets', 'QoS 2 two-phase delivery', 'NVS config migration'] },
      { ver: 'v1.1.0', date: '2026-04-20', notes: ['Initial public release', 'ECDH handshake', 'Basic OTA'] },
    ],
    color: 'switch',
  },
  {
    id: 'sensor',
    emoji: '📡',
    name: 'YKP Sensor Firmware',
    type: 'sensor',
    version: 'v1.2.1',
    date: '2026-06-01',
    size: '234 KB',
    file: 'ykp_sensor_v1.2.1.bin',
    sha256: 'b7e2d4f06a3c8190e5d7b3a1f84c2e06b9d3a7f1e28305c4d6f0a9b2e5c1d3f8',
    targets: ['ESP32', 'ESP32-S2'],
    desc: 'Sensor firmware supporting DHT22, BME280, PIR, LDR, MQ-series gas sensors. Reports readings on schedule and fires alerts on threshold breach.',
    features: [
      'DHT22 / BME280 / PIR / LDR support',
      'Configurable report intervals',
      'Threshold-based alerts',
      'QoS 1 ACK-confirmed reports',
      'AES-256-GCM encryption',
      'Device-to-device automation',
      'Local UDP broadcast mode',
    ],
    changelog: [
      { ver: 'v1.2.1', date: '2026-06-01', notes: ['BME280 pressure calibration fix', 'PIR debounce improved to 500ms'] },
      { ver: 'v1.2.0', date: '2026-05-15', notes: ['Multi-sensor support in one firmware', 'Configurable intervals via NVS'] },
      { ver: 'v1.1.0', date: '2026-04-20', notes: ['Initial release with DHT22 support'] },
    ],
    color: 'sensor',
  },
  {
    id: 'gateway',
    emoji: '🔀',
    name: 'YKP Gateway Firmware',
    type: 'gateway',
    version: 'v1.2.0',
    date: '2026-05-15',
    size: '276 KB',
    file: 'ykp_gateway_v1.2.0.bin',
    sha256: 'c9d3e5f17b4a2861f0d6c4e2a7b5f3d1e09c7a3f2d840b6e1f5a3c8d0e7b4f2a',
    targets: ['ESP32'],
    desc: 'Gateway firmware bridges cloud WebSocket traffic to local ESP-NOW mesh. Acts as a WiFi + ESP-NOW bridge — leaf nodes need no WiFi.',
    features: [
      'WebSocket ↔ ESP-NOW bridge',
      'Supports up to 20 leaf nodes',
      'Packet forwarding and routing',
      'Mesh network management',
      'AES-256-GCM on all paths',
      'Automatic leaf discovery',
      'Health aggregation for mesh',
    ],
    changelog: [
      { ver: 'v1.2.0', date: '2026-05-15', notes: ['ESP-NOW mesh routing table', 'Leaf node health aggregation', 'Fixed reconnect race condition'] },
      { ver: 'v1.1.0', date: '2026-04-20', notes: ['Initial gateway firmware with basic forwarding'] },
    ],
    color: 'gateway',
  },
  {
    id: 'motor',
    emoji: '⚙️',
    name: 'YKP Motor Firmware',
    type: 'motor',
    version: 'v1.1.2',
    date: '2026-05-28',
    size: '222 KB',
    file: 'ykp_motor_v1.1.2.bin',
    sha256: 'd0e4f6a28c5b3970g1e7d5c3b8f4e2d0f7a5b9c3e1f680a2d4f8b0c2e6a4d8f0',
    targets: ['ESP32', 'ESP32-S3'],
    desc: 'Motor controller firmware for DC motors and fans. Supports PWM speed control, direction control, and status reporting via YKP Motor Service.',
    features: [
      'PWM speed control (0–100%)',
      'Direction CW / CCW control',
      'Current sensing (optional)',
      'Start / Stop / Status commands',
      'Over-current protection',
      'AES-256-GCM encryption',
      'Health + temperature report',
    ],
    changelog: [
      { ver: 'v1.1.2', date: '2026-05-28', notes: ['Fixed PWM glitch at 0% speed', 'Added over-current threshold config'] },
      { ver: 'v1.1.0', date: '2026-05-01', notes: ['CW/CCW direction support added', 'Status reply with current speed'] },
      { ver: 'v1.0.0', date: '2026-04-20', notes: ['Initial motor firmware release'] },
    ],
    color: 'motor',
  },
  {
    id: 'universal',
    emoji: '🌐',
    name: 'YKP Universal Firmware',
    type: 'switch',
    version: 'v1.2.1',
    date: '2026-06-01',
    size: '298 KB',
    file: 'ykp_universal_v1.2.1.bin',
    sha256: 'e1f5a7b39d6c4082h2f8e6d4c9a7e5c3d1f984b3e7a5d9f1c3e7b5d0a2f6c4e8',
    targets: ['ESP32', 'ESP32-S2', 'ESP32-S3', 'ESP32-C3'],
    desc: 'Universal firmware that detects device type from NVS config and loads the correct service at boot. Best for mass production where one binary runs on all devices.',
    features: [
      'Auto-detects device type from NVS',
      'All services in one binary',
      'Relay + Sensor + Motor services',
      'Smallest overhead via lazy load',
      'AES-256-GCM encryption',
      'Full OTA self-update support',
      'BLE provisioning included',
    ],
    changelog: [
      { ver: 'v1.2.1', date: '2026-06-01', notes: ['BLE provisioning mode stable', 'Boot type detection improved', 'All security patches included'] },
    ],
    color: 'switch',
  },
]

const FLASH_STEPS = [
  {
    n: 1,
    title: 'Install esptool',
    body: 'Make sure Python and pip are installed, then install esptool:',
    code: 'pip install esptool',
  },
  {
    n: 2,
    title: 'Connect your ESP32',
    body: 'Connect ESP32 to USB. Find the COM port (Windows: Device Manager → Ports; Linux/Mac: /dev/ttyUSB0 or /dev/cu.usbserial-*).',
    code: null,
  },
  {
    n: 3,
    title: 'Erase existing flash',
    body: 'Clear the flash before flashing new firmware:',
    code: 'esptool.py --port COM3 erase_flash',
  },
  {
    n: 4,
    title: 'Flash the firmware',
    body: 'Flash the .bin file at offset 0x10000:',
    code: 'esptool.py --port COM3 --baud 921600 write_flash 0x10000 ykp_switch_v1.2.1.bin',
  },
  {
    n: 5,
    title: 'Verify SHA256',
    body: 'After download, verify the file integrity before flashing:',
    code: 'certutil -hashfile ykp_switch_v1.2.1.bin SHA256   (Windows)\nsha256sum ykp_switch_v1.2.1.bin                        (Linux/Mac)',
  },
  {
    n: 6,
    title: 'Provision device',
    body: 'After flashing, use the YKP mobile app or BLE provisioning to set WiFi credentials, Device ID, and server URL. Device will connect automatically.',
    code: null,
  },
]

function CopyBtn({ text }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }
  return (
    <button
      onClick={handleCopy}
      className="btn btn-secondary btn-sm"
      title="Copy to clipboard"
      style={{ padding: '4px 10px' }}
    >
      {copied ? <Check size={13} /> : <Copy size={13} />}
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

function FirmwareCard({ fw }) {
  const [expanded, setExpanded] = useState(false)

  const handleDownload = () => {
    /* In production: fetch from /firmware/<file> on Render */
    const link = document.createElement('a')
    link.href = `/firmware/${fw.file}`
    link.download = fw.file
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  }

  return (
    <div className={`firmware-card ${fw.color} fade-in`}>
      {/* Header */}
      <div className="fw-icon" style={{
        background: fw.id === 'sensor' ? 'rgba(0,212,255,0.12)'
                  : fw.id === 'gateway' ? 'var(--success-dim)'
                  : fw.id === 'motor' ? 'var(--warning-dim)'
                  : 'var(--primary-dim)'
      }}>
        <span style={{ fontSize: 26 }}>{fw.emoji}</span>
      </div>

      <div className="flex-between" style={{ marginBottom: 8 }}>
        <div>
          <div className="fw-name">{fw.name}</div>
          <span className="fw-version">{fw.version}</span>
        </div>
        <div style={{ textAlign: 'right', fontSize: 12, color: 'var(--text-muted)' }}>
          <div>{fw.size}</div>
          <div style={{ marginTop: 2 }}>{fw.date}</div>
        </div>
      </div>

      <div className="fw-desc">{fw.desc}</div>

      {/* Targets */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.5px', textTransform: 'uppercase', color: 'var(--text-muted)', marginBottom: 6 }}>Compatible Chips</div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {fw.targets.map(t => (
            <span key={t} style={{
              background: 'rgba(255,255,255,0.05)',
              border: '1px solid var(--border)',
              padding: '2px 8px',
              borderRadius: 4,
              fontSize: 11,
              fontFamily: 'var(--font-mono)',
              color: 'var(--text-secondary)',
            }}>{t}</span>
          ))}
        </div>
      </div>

      {/* SHA256 */}
      <div style={{ marginBottom: 16 }}>
        <div className="fw-hash-label">SHA-256 Checksum</div>
        <div className="fw-hash">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
            <span style={{ wordBreak: 'break-all', flex: 1 }}>{fw.sha256}</span>
            <CopyBtn text={fw.sha256} />
          </div>
        </div>
      </div>

      {/* Download buttons */}
      <div className="fw-btn-group">
        <button className="btn btn-primary" onClick={handleDownload}>
          <Download size={16} />
          Download .bin
        </button>
        <button
          className="btn btn-secondary"
          onClick={() => setExpanded(e => !e)}
        >
          {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
          {expanded ? 'Less Info' : 'Changelog + Features'}
        </button>
      </div>

      {/* Expanded section */}
      {expanded && (
        <div style={{ marginTop: 24, borderTop: '1px solid var(--border)', paddingTop: 20 }}>
          {/* Features */}
          <div style={{ marginBottom: 20 }}>
            <div className="section-title" style={{ fontSize: 13, marginBottom: 12 }}><Shield size={14} />Included Features</div>
            <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
              {fw.features.map(f => (
                <li key={f} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--text-secondary)' }}>
                  <CheckCircle size={13} color="var(--success)" style={{ flexShrink: 0 }} />
                  {f}
                </li>
              ))}
            </ul>
          </div>

          {/* Changelog */}
          <div>
            <div className="section-title" style={{ fontSize: 13, marginBottom: 12 }}><Info size={14} />Changelog</div>
            <div className="changelog-item" style={{ borderLeft: '2px solid var(--border)', paddingLeft: 16 }}>
              {fw.changelog.map((c, i) => (
                <div key={c.ver} style={{ marginBottom: i < fw.changelog.length - 1 ? 16 : 0 }}>
                  <div className="changelog-version">{c.ver}</div>
                  <div className="changelog-date">{c.date}</div>
                  <ul className="changelog-list">
                    {c.notes.map(n => <li key={n}>{n}</li>)}
                  </ul>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default function Firmware() {
  const [filter, setFilter] = useState('all')
  const [searchTerm, setSearchTerm] = useState('')

  const filtered = FIRMWARE.filter(fw => {
    const matchType = filter === 'all' || fw.id === filter
    const matchSearch = fw.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
                        fw.id.toLowerCase().includes(searchTerm.toLowerCase())
    return matchType && matchSearch
  })

  return (
    <div className="page">
      {/* ── Hero banner ── */}
      <div style={{
        background: 'linear-gradient(135deg, rgba(124,111,255,0.12), rgba(0,212,255,0.08))',
        border: '1px solid rgba(124,111,255,0.2)',
        borderRadius: 'var(--radius-lg)',
        padding: '32px 36px',
        marginBottom: 28,
        position: 'relative',
        overflow: 'hidden',
      }}>
        <div style={{
          position: 'absolute',
          top: -40, right: -40,
          width: 200, height: 200,
          background: 'radial-gradient(circle, rgba(124,111,255,0.15), transparent 70%)',
          borderRadius: '50%',
        }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 12 }}>
          <div style={{
            width: 54, height: 54,
            background: 'linear-gradient(135deg, var(--primary), var(--secondary))',
            borderRadius: 14,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 26, boxShadow: '0 0 24px var(--primary-glow)',
          }}>⬇️</div>
          <div>
            <h2 style={{ fontSize: 24, fontWeight: 800, color: 'var(--text-primary)' }}>
              YKP v5 Firmware Downloads
            </h2>
            <p style={{ fontSize: 14, color: 'var(--text-secondary)', marginTop: 2 }}>
              Official signed firmware builds for all ESP32 device types
            </p>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
          {[
            { icon: '🔐', label: 'ECDSA signed — verified safe' },
            { icon: '🔒', label: 'AES-256-GCM encrypted' },
            { icon: '⚡', label: 'Flash in under 60 seconds' },
            { icon: '♻️', label: 'OTA self-update support' },
          ].map(b => (
            <div key={b.label} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-secondary)' }}>
              <span>{b.icon}</span>{b.label}
            </div>
          ))}
        </div>
      </div>

      {/* ── Security notice ── */}
      <div className="alert-banner info" style={{ marginBottom: 24 }}>
        <Shield size={16} />
        <span>
          All firmware files are ECDSA-signed with the YKP server private key. Your ESP32 will reject any unsigned firmware automatically.
          Always verify the SHA-256 checksum before flashing.
        </span>
      </div>

      {/* ── Filters ── */}
      <div className="flex-between" style={{ marginBottom: 24, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {['all', 'switch', 'sensor', 'gateway', 'motor', 'universal'].map(f => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`btn ${filter === f ? 'btn-primary' : 'btn-secondary'} btn-sm`}
              style={{ textTransform: 'capitalize' }}
            >
              {f}
            </button>
          ))}
        </div>
        <input
          className="input"
          style={{ width: 220 }}
          placeholder="Search firmware..."
          value={searchTerm}
          onChange={e => setSearchTerm(e.target.value)}
        />
      </div>

      {/* ── Firmware Grid ── */}
      <div className="grid-auto" style={{ marginBottom: 40 }}>
        {filtered.map(fw => <FirmwareCard key={fw.id} fw={fw} />)}
      </div>

      {/* ── Flash Instructions ── */}
      <div className="card" style={{ marginBottom: 28 }}>
        <div className="section-title" style={{ marginBottom: 24 }}>
          <Terminal size={18} />
          How to Flash — Step by Step
        </div>
        <div className="steps">
          {FLASH_STEPS.map(s => (
            <div className="step" key={s.n}>
              <div className="step-num">{s.n}</div>
              <div className="step-body">
                <h4>{s.title}</h4>
                <p>{s.body}</p>
                {s.code && (
                  <div style={{ position: 'relative' }}>
                    <code>{s.code}</code>
                    <div style={{ position: 'absolute', top: 8, right: 8 }}>
                      <CopyBtn text={s.code} />
                    </div>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* ── Required NVS Config ── */}
      <div className="grid-2">
        <div className="card">
          <div className="section-title" style={{ marginBottom: 16 }}><Info size={18} />Required NVS Configuration</div>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 16 }}>
            After flashing, these keys must be set in NVS before the device will connect. Use BLE provisioning or <code style={{ fontFamily: 'var(--font-mono)', background: 'rgba(0,0,0,0.3)', padding: '1px 6px', borderRadius: 4, fontSize: 11 }}>idf.py menuconfig</code>.
          </p>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {[
              { key: 'device_id',    value: 'SW001',                                   req: true  },
              { key: 'device_type',  value: 'switch / sensor / motor / gateway',        req: true  },
              { key: 'device_name',  value: '"Living Room Switch"',                    req: false },
              { key: 'server_url',   value: getWebSocketUrl(),                         req: true  },
              { key: 'wifi_ssid',    value: 'Your WiFi SSID',                          req: true  },
              { key: 'wifi_password',value: 'Your WiFi Password',                      req: true  },
            ].map(r => (
              <div key={r.key} style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px 12px',
                background: 'rgba(0,0,0,0.2)',
                borderRadius: 6,
                border: '1px solid var(--border)',
              }}>
                <div style={{ flex: '0 0 140px', fontFamily: 'var(--font-mono)', fontSize: 12, color: r.req ? 'var(--primary)' : 'var(--secondary)' }}>
                  {r.key}
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{r.value}</div>
                {r.req && <span style={{ marginLeft: 'auto', fontSize: 10, color: 'var(--danger)', fontWeight: 700, background: 'var(--danger-dim)', padding: '1px 6px', borderRadius: 4 }}>Required</span>}
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <div className="section-title" style={{ marginBottom: 16 }}><Shield size={18} />Security Verification</div>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 16 }}>
            The YKP Router server's public key is hardcoded in every firmware build. The device will only accept OTA firmware signed with the matching private key.
          </p>
          <div style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.5px', textTransform: 'uppercase', color: 'var(--text-muted)', marginBottom: 6 }}>Server Public Key (P-256)</div>
            <div className="fw-hash">
              04:a3:f8:c1:d9:4e:2b:70:65:f1:9a:4c:83:d6:e0:f2:b8:17:a5:c9:e3:d1:f0:47:8b:2c:6e:9a:4f:18:3d:0e:25:b7:e2:d4:f0:6a:3c:81:90:e5:d7:b3:a1:f8:4c:2e:06
            </div>
          </div>
          <div className="alert-banner success" style={{ margin: 0 }}>
            <CheckCircle size={16} />
            <span style={{ fontSize: 12 }}>ECDSA-P256-SHA256 — firmware is cryptographically verified before install</span>
          </div>
        </div>
      </div>
    </div>
  )
}
