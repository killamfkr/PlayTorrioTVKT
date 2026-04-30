-- Run in Supabase → SQL Editor if sync fails with:
--   column user_settings.stremio_addons does not exist
--
-- Safe to run multiple times (IF NOT EXISTS).

alter table public.user_settings
  add column if not exists stremio_addons jsonb not null default '[]'::jsonb;

alter table public.user_settings
  add column if not exists default_stream_source text;

-- Optional: touch updated_at if you add a trigger later
-- alter table public.user_settings add column if not exists updated_at timestamptz default now();
