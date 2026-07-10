#!/bin/sh
set -e

# Load apps/backend/.env for keys the orchestrator did not already set. In k8s, env vars
# from the pod spec must win over a mounted secret file so updated credentials take effect
# without rewriting the whole .env secret.
ENV_FILE="/app/apps/backend/.env"
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%$'\r'}"
    case "$line" in
      ''|\#*) continue ;;
    esac
    key="${line%%=*}"
    val="${line#*=}"
    case "$key" in
      *[!A-Za-z0-9_]*) continue ;;
    esac
    eval "if [ -z \"\${$key+x}\" ]; then export $key=\"$val\"; fi"
  done < "$ENV_FILE"
fi

cd /app/packages/db && bunx prisma db push --skip-generate
cd /app/apps/backend && exec bun start
