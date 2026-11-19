create table users (
    id uuid primary key,
    email varchar(254) not null unique check (email = lower(email)),
    display_name varchar(100) not null,
    password_hash varchar(100) not null,
    created_at timestamptz not null default now()
);
create table roles (name varchar(20) primary key);
insert into roles (name) values ('ADMIN'), ('API_OWNER'), ('CONSUMER');
create table user_roles (
    user_id uuid not null references users(id) on delete cascade,
    role varchar(20) not null references roles(name),
    primary key (user_id, role)
);
create table refresh_sessions (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);
create index refresh_sessions_user on refresh_sessions(user_id);
create index refresh_sessions_expiry on refresh_sessions(expires_at);
create table refresh_tokens (
    token_hash char(64) primary key,
    session_id uuid not null references refresh_sessions(id) on delete cascade,
    used_at timestamptz,
    created_at timestamptz not null default now()
);
create index refresh_tokens_session on refresh_tokens(session_id);
create table authentication_audit (
    id bigint generated always as identity primary key,
    user_id uuid references users(id) on delete set null,
    action varchar(30) not null,
    occurred_at timestamptz not null default now()
);
create index authentication_audit_user on authentication_audit(user_id, occurred_at desc);
