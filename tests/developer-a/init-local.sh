#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
umask 077
mkdir -p .local
if [[ -f .local/.env ]]; then
  printf 'Using existing %s\n' .local/.env
  exit 0
fi
if [[ -e .local/sentinel.p12 ]]; then
  printf 'A signing keystore already exists. Restore its matching environment file first.\n' >&2
  exit 1
fi
export SENTINEL_INIT_KEY_PASSWORD
SENTINEL_INIT_KEY_PASSWORD=$(openssl rand -hex 32)
keytool -genkeypair -alias sentinel -keyalg RSA -keysize 3072 -validity 1825 -dname 'CN=API Sentinel Local' \
  -storetype PKCS12 -keystore .local/sentinel.p12 -storepass:env SENTINEL_INIT_KEY_PASSWORD
chmod 644 .local/sentinel.p12
{
  printf '%s\n' 'JWT_ISSUER=http://localhost:5173' 'JWT_KEYSTORE_PATH=../.local/sentinel.p12' \
    "JWT_KEYSTORE_PASSWORD=$SENTINEL_INIT_KEY_PASSWORD" 'SECURE_COOKIE=false' \
    'CORS_ORIGINS=http://localhost:5173' 'EUREKA_SELF_PRESERVATION=false' 'RABBITMQ_USER=sentinel_local'
  for name in POSTGRES_PASSWORD IDENTITY_DB_PASSWORD REGISTRY_DB_PASSWORD ANALYZER_DB_PASSWORD RABBITMQ_PASSWORD \
    ANALYZER_REGISTRY_KEY REGISTRY_ANALYZER_KEY IMPACT_SERVICE_KEY METRICS_SERVICE_KEY; do
    printf '%s=%s\n' "$name" "$(openssl rand -hex 32)"
  done
} > .local/.env
unset SENTINEL_INIT_KEY_PASSWORD
printf 'Created local environment at %s\n' .local/.env
