\set ON_ERROR_STOP on
\getenv identity_password IDENTITY_DB_PASSWORD
\getenv registry_password REGISTRY_DB_PASSWORD
\getenv analyzer_password ANALYZER_DB_PASSWORD
create role sentinel_identity login password :'identity_password';
create role sentinel_registry login password :'registry_password';
create role sentinel_analyzer login password :'analyzer_password';
revoke all on database sentinel from public;
grant connect on database sentinel to sentinel_identity, sentinel_registry, sentinel_analyzer;
revoke all on schema public from public;
create schema identity_db authorization sentinel_identity;
create schema registry_db authorization sentinel_registry;
create schema analyzer_db authorization sentinel_analyzer;
