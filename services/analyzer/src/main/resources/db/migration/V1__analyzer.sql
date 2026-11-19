create table analysis_jobs (
    id uuid primary key,
    api_id uuid not null,
    baseline_version_id uuid,
    new_version_id uuid not null,
    owner_id uuid not null,
    submitted_by uuid not null,
    correlation_id uuid not null,
    status varchar(20) not null default 'QUEUED' check (status in ('QUEUED','RUNNING','RETRY_PENDING','COMPLETED','FAILED')),
    attempt_count integer not null default 0 check (attempt_count >= 0),
    created_at timestamptz not null default now(),
    started_at timestamptz,
    completed_at timestamptz,
    error varchar(1000),
    outcome varchar(20) check (outcome in ('NO_BASELINE','COMPARED')),
    breaking_count integer not null default 0 check (breaking_count >= 0),
    potentially_breaking_count integer not null default 0 check (potentially_breaking_count >= 0),
    non_breaking_count integer not null default 0 check (non_breaking_count >= 0),
    next_attempt_at timestamptz not null default now(),
    lease_token uuid,
    lease_until timestamptz,
    unique nulls not distinct (api_id, new_version_id, baseline_version_id),
    check (new_version_id <> baseline_version_id),
    check ((status in ('COMPLETED','FAILED')) = (completed_at is not null)),
    check ((status = 'RUNNING') = (lease_token is not null and lease_until is not null))
);
create index analysis_due on analysis_jobs(next_attempt_at, id) where status in ('QUEUED','RETRY_PENDING');
create index analysis_expired on analysis_jobs(lease_until) where status = 'RUNNING';
create index analysis_by_owner on analysis_jobs(owner_id, created_at desc, id desc);
create index analysis_by_api on analysis_jobs(api_id, created_at desc, id desc);
create table analysis_findings (
    id uuid primary key,
    analysis_id uuid not null references analysis_jobs(id),
    ordinal integer not null,
    severity varchar(24) not null check (severity in ('BREAKING','POTENTIALLY_BREAKING','NON_BREAKING')),
    category varchar(80) not null,
    http_method varchar(12),
    endpoint_path text,
    json_path text not null,
    description text not null,
    old_value text,
    new_value text,
    unique (analysis_id, ordinal)
);
create index finding_filters on analysis_findings(analysis_id, severity, category, ordinal);
create table analysis_artifacts (
    analysis_id uuid primary key references analysis_jobs(id),
    schema_version integer not null,
    report bytea not null
);
create table processed_events (
    id uuid primary key,
    processed_at timestamptz not null default now()
);
