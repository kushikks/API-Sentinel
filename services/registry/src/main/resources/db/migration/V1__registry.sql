create table apis (
    id uuid primary key,
    owner_id uuid not null,
    name varchar(100) not null,
    description varchar(1000),
    base_url varchar(2048),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index api_owner_name on apis(owner_id, lower(name));
create index api_owner_created on apis(owner_id, created_at desc, id desc);
create table api_versions (
    id uuid primary key,
    api_id uuid not null references apis(id),
    upload_order bigint generated always as identity,
    version_label varchar(80) not null,
    specification_hash char(64) not null,
    created_by uuid not null,
    status varchar(20) not null default 'REGISTERED' check (status = 'REGISTERED'),
    created_at timestamptz not null default now(),
    unique (api_id, id)
);
create unique index api_version_label on api_versions(api_id, lower(version_label));
create index api_version_order on api_versions(api_id, upload_order desc);
create table specification_artifacts (
    id uuid primary key,
    version_id uuid not null unique references api_versions(id),
    content bytea not null,
    content_type varchar(40) not null check (content_type in ('application/json','application/yaml')),
    size_bytes integer not null check (size_bytes between 1 and 1048576 and size_bytes = octet_length(content)),
    checksum char(64) not null
);
create table analysis_requests (
    id uuid primary key,
    api_id uuid not null,
    version_id uuid not null,
    baseline_version_id uuid,
    submitted_by uuid not null,
    correlation_id uuid not null unique,
    submission_order bigint generated always as identity,
    created_at timestamptz not null default now(),
    foreign key (api_id, version_id) references api_versions(api_id, id),
    foreign key (api_id, baseline_version_id) references api_versions(api_id, id),
    unique nulls not distinct (api_id, version_id, baseline_version_id),
    check (version_id <> baseline_version_id)
);
create index analysis_requests_latest on analysis_requests(api_id, version_id, submission_order desc);
