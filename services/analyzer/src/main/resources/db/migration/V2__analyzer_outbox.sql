create table outbox_events (
    id uuid primary key,
    routing_key varchar(100) not null,
    body bytea not null,
    attempts integer not null default 0,
    created_at timestamptz not null default now(),
    next_attempt_at timestamptz not null default now(),
    published_at timestamptz
);
create index outbox_due on outbox_events(next_attempt_at, id) where published_at is null;
