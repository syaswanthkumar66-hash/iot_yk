import { useState, useEffect } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import {
  LayoutDashboard, Cpu, Download, Activity, Users,
  Zap, Upload, Server
} from 'lucide-react'
import { getBackendUrl, setBackendUrl } from '../lib/api'

const NAV = [
  {
    section: 'Main',
    items: [
      { to: '/dashboard',  icon: LayoutDashboard, label: 'Dashboard',  badge: null },
      { to: '/devices',    icon: Cpu,             label: 'Devices',    badge: null },
      { to: '/health',     icon: Activity,        label: 'Health',     badge: null },
    ]
  },
  {
    section: 'Control',
    items: [
      { to: '/groups',     icon: Users, label: 'Groups',     badge: null },
      { to: '/automation', icon: Zap,   label: 'Automation', badge: null },
    ]
  },
  {
    section: 'Firmware',
    items: [
      { to: '/firmware',   icon: Download, label: 'Firmware Download', badge: 'NEW', badgeClass: 'green' },
      { to: '/ota',        icon: Upload,   label: 'OTA Manager',       badge: null  },
    ]
  }
]

const PAGE_TITLES = {
  '/dashboard':  { title: 'Dashboard',          sub: 'Real-time overview of your IoT network' },
  '/devices':    { title: 'Devices',            sub: 'All registered ESP32 devices' },
  '/health':     { title: 'Health Monitoring',  sub: 'CPU, RAM, RSSI and more — every 30 seconds' },
  '/groups':     { title: 'Group Control',       sub: 'Control multiple devices with one command' },
  '/automation': { title: 'Automation Engine',  sub: 'IF/THEN rules — sensors trigger actions' },
  '/firmware':   { title: 'Firmware Download',  sub: 'Download official YKP v5 ESP32 firmware builds' },
  '/ota':        { title: 'OTA Manager',         sub: 'Push firmware updates over-the-air' },
}

export default function Layout() {
  const location = useLocation()
  const page = PAGE_TITLES[location.pathname] || { title: 'YKP Dashboard', sub: '' }

  const [serverUrl, setServerUrl] = useState(getBackendUrl())
  const [status, setStatus] = useState('checking') // 'checking' | 'connected' | 'offline' | 'waking'

  const checkServerStatus = async () => {
    try {
      const controller = new AbortController()
      const id = setTimeout(() => controller.abort(), 4000) // 4s timeout

      const res = await fetch(`${getBackendUrl()}/health`, { signal: controller.signal })
      clearTimeout(id)

      if (res.ok) {
        setStatus('connected')
      } else {
        setStatus('waking') // Server is online but returned error, or spin-up phase
      }
    } catch (err) {
      // Aborted means server did not respond in 4s (likely sleeping/spinning up on Render free tier)
      if (err.name === 'AbortError') {
        setStatus('waking')
      } else {
        setStatus('offline')
      }
    }
  }

  useEffect(() => {
    checkServerStatus()
    const interval = setInterval(checkServerStatus, 10000) // check every 10 seconds
    return () => clearInterval(interval)
  }, [serverUrl])

  const handleStatusClick = () => {
    const newUrl = prompt(
      'Customize YKP Router Backend URL:\n(Leave empty to reset to default Render deployment)',
      getBackendUrl()
    )
    if (newUrl !== null) {
      setBackendUrl(newUrl)
      setServerUrl(getBackendUrl())
      setStatus('checking')
      // Refresh window to reload all data hooks with the new URL
      window.location.reload()
    }
  }

  return (
    <div className="layout">
      {/* ── Sidebar ── */}
      <aside className="sidebar">
        <div className="sidebar-logo">
          <div className="logo-icon">⚡</div>
          <div className="logo-text">
            <h1>YKP v5</h1>
            <span>IoT Management Platform</span>
          </div>
        </div>

        <nav className="sidebar-nav">
          {NAV.map(group => (
            <div key={group.section}>
              <div className="nav-section-label">{group.section}</div>
              {group.items.map(({ to, icon: Icon, label, badge, badgeClass }) => (
                <NavLink
                  key={to}
                  to={to}
                  className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
                >
                  <Icon size={18} />
                  {label}
                  {badge && (
                    <span className={`nav-badge ${badgeClass || ''}`}>{badge}</span>
                  )}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div 
            className="server-status clickable" 
            onClick={handleStatusClick}
            title="Click to customize Render Backend URL"
            style={{ cursor: 'pointer', padding: '6px 8px', borderRadius: '6px', transition: 'background 0.2s' }}
          >
            <div className={`status-dot ${status}`}></div>
            <span>
              {status === 'checking' && 'Pinging Router...'}
              {status === 'connected' && 'YKP Router · Connected'}
              {status === 'waking' && 'Waking up Web Worker...'}
              {status === 'offline' && 'Router Offline · Click to edit'}
            </span>
          </div>
          <div style={{ marginTop: 10, fontSize: 11, color: 'var(--text-muted)' }}>
            <span>{serverUrl.replace(/^https?:\/\//, '')}</span>
          </div>
        </div>
      </aside>

      {/* ── Main ── */}
      <div className="main-content">
        <header className="topbar">
          <div className="topbar-left">
            <h2>{page.title}</h2>
            <p>{page.sub}</p>
          </div>
          <div className="topbar-right">
            <a
              href={`${getBackendUrl()}/health`}
              target="_blank"
              rel="noopener noreferrer"
              className="topbar-btn"
            >
              <Server size={14} />
              Server Status
            </a>
            <NavLink to="/firmware" className="topbar-btn primary">
              <Download size={14} />
              Download Firmware
            </NavLink>
          </div>
        </header>

        <main style={{ flex: 1 }}>
          <Outlet />
        </main>
      </div>
    </div>
  )
}
