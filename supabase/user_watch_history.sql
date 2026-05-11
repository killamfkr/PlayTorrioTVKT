-- Continue watching / resume sync (same shape as PlayTorrio mobile `user_watch_history`).
-- Run in the Supabase SQL Editor if this table is missing.

create table if not exists public.user_watch_history (
  user_id uuid not null references auth.users (id) on delete cascade,
  profile_id int not null default 1,
  entries jsonb not null default '[]'::jsonb,
  updated_at timestamptz default now(),
  primary key (user_id, profile_id)
);

alter table public.user_watch_history enable row level security;

drop policy if exists "user_watch_history_own_row" on public.user_watch_history;
create policy "user_watch_history_own_row"
  on public.user_watch_history for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

notify pgrst, 'reload schema';
