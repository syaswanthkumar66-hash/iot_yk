-- ═══════════════════════════════════════════════════════════════
-- YKP v5 Supabase Schema
-- Run this in the Supabase SQL editor once to set up all tables.
-- ═══════════════════════════════════════════════════════════════

-- ── Enable extensions ───────────────────────────────────────────
create extension if not exists "pgcrypto";
create extension if not exists "uuid-ossp";

-- ── devices ─────────────────────────────────────────────────────
create table if not exists devices (
    id           bigint generated always as identity primary key,
    device_id    text not null unique,
    device_name  text,
    device_type  text not null default 'switch',  -- switch/sensor/motor/gateway
    firmware_ver text default '1.0.0',
    is_online    boolean default false,
    ip_address   text,
    last_seen    timestamptz,
    capabilities integer default 0,               -- bitmask
    group_ids    integer[] default '{}',
    location     text,
    notes        text,
    created_at   timestamptz default now()
);

-- ── device_health ────────────────────────────────────────────────
create table if not exists device_health (
    id           bigint generated always as identity primary key,
    device_id    text not null references devices(device_id) on delete cascade,
    cpu_usage    real,          -- percentage
    free_heap    integer,       -- bytes
    min_heap     integer,       -- bytes
    rssi         smallint,      -- dBm
    temperature  real,          -- Celsius (ESP32 internal)
    battery      real,          -- percentage
    packet_loss  real,          -- percentage
    rtt_ms       real,          -- ms
    uptime_sec   bigint,        -- seconds since boot
    restart_count integer default 0,
    recorded_at  timestamptz default now()
);

create index if not exists idx_health_device_time
    on device_health (device_id, recorded_at desc);

-- Retention: auto-delete records older than 30 days
-- (Enable pg_cron on Supabase to run this)
-- select cron.schedule('cleanup-health', '0 2 * * *',
--   'delete from device_health where recorded_at < now() - interval ''30 days''');

-- ── device_sessions ──────────────────────────────────────────────
create table if not exists device_sessions (
    id               bigint generated always as identity primary key,
    device_id        text not null references devices(device_id) on delete cascade,
    session_id       text not null,
    session_key_hash text not null,   -- SHA-256 of AES key (never store raw key)
    packet_counter   bigint default 0,
    is_active        boolean default true,
    started_at       timestamptz default now(),
    expires_at       timestamptz default now() + interval '1 hour'
);

create index if not exists idx_sessions_device
    on device_sessions (device_id, is_active);

-- ── device_state ─────────────────────────────────────────────────
create table if not exists device_state (
    device_id    text primary key references devices(device_id) on delete cascade,
    relay_state  boolean default false,
    motor_speed  smallint default 0,       -- 0-100 %
    motor_dir    text default 'STOP',      -- CW / CCW / STOP
    sensor_data  jsonb,                    -- latest sensor readings
    updated_at   timestamptz default now()
);

-- ── groups ───────────────────────────────────────────────────────
create table if not exists device_groups (
    id         bigint generated always as identity primary key,
    group_id   integer not null unique,
    group_name text not null,
    members    text[] default '{}',        -- array of device_ids
    is_active  boolean default true,
    created_at timestamptz default now()
);

-- ── automation_rules ─────────────────────────────────────────────
create table if not exists automation_rules (
    id           bigint generated always as identity primary key,
    rule_id      text default gen_random_uuid()::text,
    rule_name    text not null,
    trigger_device  text references devices(device_id),
    trigger_service smallint,
    trigger_action  smallint,
    trigger_payload jsonb,               -- condition: {key: value}
    target_device   text references devices(device_id),
    target_service  smallint,
    target_action   smallint,
    target_payload  jsonb,
    is_enabled   boolean default true,
    last_run_at  timestamptz,
    run_count    bigint default 0,
    created_at   timestamptz default now()
);

-- ── ota_jobs ─────────────────────────────────────────────────────
create table if not exists ota_jobs (
    id              bigint generated always as identity primary key,
    job_id          text default gen_random_uuid()::text unique,
    device_id       text not null references devices(device_id),
    firmware_url    text not null,
    firmware_ver    text not null,
    firmware_sha256 text not null,
    firmware_sig    text not null,           -- ECDSA P-256 hex
    firmware_size   integer not null,
    chunk_size      integer default 4096,
    chunks_total    integer,
    chunks_sent     integer default 0,
    status          text default 'pending',  -- pending/in_progress/complete/failed
    error_msg       text,
    created_at      timestamptz default now(),
    started_at      timestamptz,
    completed_at    timestamptz
);

-- ── audit_logs ───────────────────────────────────────────────────
create table if not exists audit_logs (
    id         bigint generated always as identity primary key,
    device_id  text,
    action     text not null,
    payload    jsonb,
    result     text default 'success',
    ip_address text,
    created_at timestamptz default now()
);

create index if not exists idx_audit_device_time
    on audit_logs (device_id, created_at desc);

-- ── RLS Policies ──────────────────────────────────────────────────
-- Enable RLS on all tables
alter table devices          enable row level security;
alter table device_health    enable row level security;
alter table device_sessions  enable row level security;
alter table device_state     enable row level security;
alter table device_groups    enable row level security;
alter table automation_rules enable row level security;
alter table ota_jobs         enable row level security;
alter table audit_logs       enable row level security;

-- Allow service role (server) to do everything
create policy "service_role_all" on devices
    for all to service_role using (true);

create policy "service_role_all" on device_health
    for all to service_role using (true);

create policy "service_role_all" on device_sessions
    for all to service_role using (true);

create policy "service_role_all" on device_state
    for all to service_role using (true);

create policy "service_role_all" on device_groups
    for all to service_role using (true);

create policy "service_role_all" on automation_rules
    for all to service_role using (true);

create policy "service_role_all" on ota_jobs
    for all to service_role using (true);

create policy "service_role_all" on audit_logs
    for all to service_role using (true);

-- Authenticated users can read devices, health, groups
create policy "auth_read_devices" on devices
    for select to authenticated using (true);

create policy "auth_read_health" on device_health
    for select to authenticated using (true);

create policy "auth_read_groups" on device_groups
    for select to authenticated using (true);

create policy "auth_read_rules" on automation_rules
    for select to authenticated using (true);

-- ── Realtime ────────────────────────────────────────────────────
-- Enable realtime for dashboard live updates
alter publication supabase_realtime add table devices;
alter publication supabase_realtime add table device_health;
alter publication supabase_realtime add table device_state;
alter publication supabase_realtime add table ota_jobs;

-- ── Seed data (demo) ─────────────────────────────────────────────
insert into devices (device_id, device_name, device_type, firmware_ver, capabilities)
values
  ('SW001', 'Living Room Switch', 'switch',  '1.2.1', 65),
  ('SW002', 'Bedroom Switch',     'switch',  '1.2.1', 65),
  ('SW003', 'Kitchen Switch',     'switch',  '1.2.0', 65),
  ('SN001', 'Motion Sensor',      'sensor',  '1.2.1', 66),
  ('SN002', 'Temp & Humidity',    'sensor',  '1.2.1', 66),
  ('GW001', 'Main Gateway',       'gateway', '1.2.0', 96),
  ('MC001', 'Fan Motor',          'motor',   '1.1.2', 68)
on conflict (device_id) do nothing;

insert into device_groups (group_id, group_name, members)
values
  (100, 'Living Room Lights', ARRAY['SW001', 'SW002']),
  (101, 'Kitchen Devices',    ARRAY['SW003']),
  (102, 'All Switches',       ARRAY['SW001', 'SW002', 'SW003'])
on conflict (group_id) do nothing;

insert into automation_rules (rule_name, trigger_device, trigger_service,
                               trigger_action, target_device, target_service,
                               target_action, is_enabled)
values
  ('Motion → Light ON', 'SN001', 2, 1, 'SW001', 1, 1, true),
  ('Temp>30 → Fan ON',  'SN002', 2, 1, 'MC001', 3, 1, true),
  ('Temp<25 → Fan OFF', 'SN002', 2, 1, 'MC001', 3, 2, true)
on conflict do nothing;
