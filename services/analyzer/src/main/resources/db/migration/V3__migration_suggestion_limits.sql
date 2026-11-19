create table migration_suggestion_limits (
    scope text primary key,
    ready_at timestamptz not null
);
