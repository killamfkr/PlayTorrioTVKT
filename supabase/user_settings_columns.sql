-- Run in Supabase → SQL Editor if sync fails with:
--   column user_settings.stremio_addons does not exist  (Postgres 42703)
--
-- Safe to run multiple times (IF NOT EXISTS).

alter table public.user_settings
  add column if not exists stremio_addons jsonb not null default '[]'::jsonb;

alter table public.user_settings
  add column if not exists default_stream_source text;

-- Refresh PostgREST so /rest/v1 sees new columns immediately (hosted Supabase).
notify pgrst, 'reload schema';

-- Verify (optional — run after):
-- select column_name, data_type
-- from information_schema.columns
-- where table_schema = 'public' and table_name = 'user_settings'
-- order by ordinal_position;
