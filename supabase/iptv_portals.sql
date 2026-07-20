-- PlayTorrio Cloud schema for IPTV portal sync (Supabase SQL editor)

create table if not exists public.iptv_portals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade not null,
  url text not null,
  username text not null,
  password text not null default '',
  kind text not null default 'xtream',
  display_name text,
  source text default 'PlayTorrio Cloud',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

alter table public.iptv_portals enable row level security;

create policy "Users read own iptv portals"
  on public.iptv_portals for select
  using (auth.uid() = user_id);

create policy "Users insert own iptv portals"
  on public.iptv_portals for insert
  with check (auth.uid() = user_id);

create policy "Users update own iptv portals"
  on public.iptv_portals for update
  using (auth.uid() = user_id);

create policy "Users delete own iptv portals"
  on public.iptv_portals for delete
  using (auth.uid() = user_id);

-- Optional single-portal fallback on profiles:
-- alter table public.profiles add column if not exists iptv_url text;
-- alter table public.profiles add column if not exists iptv_username text;
-- alter table public.profiles add column if not exists iptv_password text;
-- alter table public.profiles add column if not exists iptv_kind text default 'xtream';
-- alter table public.profiles add column if not exists iptv_name text;
