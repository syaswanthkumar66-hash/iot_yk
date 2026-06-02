import { NavLink, Outlet, useLocation } from 'react-router-dom'
import {
  LayoutDashboard, Cpu, Download, Activity, Users,
  Zap, Upload, Radio, Server, ExternalLink
} from 'lucide-react'

const NAV = [
  {
    section: 'Main',
    items: [
      { to: '/dashboard',  icon: LayoutDashboard, label: 'Dashboard',  badge: null },
      { to: '/devices',    icon: Cpu,             label: 'Devices',    badge: '8'  },
      { to: '/health',     icon: Activity,        label: 'Health',     badge: null },
    ]
  },
  {
    section: 'Control',
    items: [
      { to: '/groups',     icon: Users, label: 'Groups',     badge: '3'  },
      { to: '/automation', icon: Zap,   label: 'Automation', badge: '5'  },
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
          <div className="server-status">
            <div className="status-dot"></div>
            <span>YKP Router · Connected</span>
          </div>
          <div style={{ marginTop: 10, fontSize: 11, color: 'var(--text-muted)' }}>
            <span>render.com · ykp-router</span>
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
              href="https://ykp-router.onrender.com/health"
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
