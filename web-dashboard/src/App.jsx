import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Devices from './pages/Devices'
import Firmware from './pages/Firmware'
import Health from './pages/Health'
import Groups from './pages/Groups'
import Automation from './pages/Automation'
import OTA from './pages/OTA'
import Register from './pages/Register'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard"  element={<Dashboard />} />
          <Route path="devices"    element={<Devices />} />
          <Route path="firmware"   element={<Firmware />} />
          <Route path="health"     element={<Health />} />
          <Route path="groups"     element={<Groups />} />
          <Route path="automation" element={<Automation />} />
          <Route path="ota"        element={<OTA />} />
          <Route path="register"   element={<Register />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
