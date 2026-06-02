import { createClient, SupabaseClient } from '@supabase/supabase-js'
import dotenv from 'dotenv'
dotenv.config()

const supabaseUrl  = process.env.SUPABASE_URL  || 'https://placeholder.supabase.co'
const supabaseKey  = process.env.SUPABASE_SERVICE_KEY || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.placeholder'

if (!process.env.SUPABASE_URL || !process.env.SUPABASE_SERVICE_KEY) {
  console.warn('[supabase] WARNING: SUPABASE_URL or SUPABASE_SERVICE_KEY environment variables are not set. Using placeholder credentials. Database operations will fail.')
}

export const supabase: SupabaseClient = createClient(supabaseUrl, supabaseKey, {
  auth: { persistSession: false },
})

// ── Database helpers ──────────────────────────

export async function getDevice(deviceId: string) {
  const { data } = await supabase
    .from('devices')
    .select('*')
    .eq('device_id', deviceId)
    .single()
  return data
}

export async function upsertDevice(deviceId: string, fields: Record<string, unknown>) {
  await supabase.from('devices').upsert({ device_id: deviceId, ...fields },
                                        { onConflict: 'device_id' })
}

export async function setDeviceOnline(deviceId: string, ip: string, online: boolean) {
  await supabase.from('devices').update({
    is_online: online,
    ip_address: ip,
    last_seen: new Date().toISOString(),
  }).eq('device_id', deviceId)
}

export async function insertHealthRecord(deviceId: string, health: Record<string, unknown>) {
  await supabase.from('device_health').insert({ device_id: deviceId, ...health })
}

export async function getActiveSession(deviceId: string) {
  const { data } = await supabase
    .from('device_sessions')
    .select('*')
    .eq('device_id', deviceId)
    .eq('is_active', true)
    .single()
  return data
}

export async function createSession(deviceId: string, sessionId: string,
                                    keyHash: string) {
  await supabase.from('device_sessions').insert({
    device_id:        deviceId,
    session_id:       sessionId,
    session_key_hash: keyHash,
    packet_counter:   0,
    is_active:        true,
    started_at:       new Date().toISOString(),
    expires_at:       new Date(Date.now() + 3_600_000).toISOString(),
  })
}

export async function invalidateSessions(deviceId: string) {
  await supabase.from('device_sessions')
    .update({ is_active: false })
    .eq('device_id', deviceId)
}

export async function getAutomationRules() {
  const { data } = await supabase
    .from('automation_rules')
    .select('*')
    .eq('is_enabled', true)
  return data ?? []
}

export async function logAudit(deviceId: string, action: string,
                               payload: unknown, result = 'success') {
  await supabase.from('audit_logs').insert({
    device_id: deviceId,
    action,
    payload,
    result,
    created_at: new Date().toISOString(),
  })
}
