#!/usr/bin/env bash
set -euo pipefail
deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$deploy_dir"
compose=(docker compose --env-file .env -f compose.yaml)

# Run inside Nginx to exercise the same /api gateway used by the browser.
"${compose[@]}" exec -T web sh -ec '
    wget -q -O /dev/null http://127.0.0.1/health/live
    wget -q -O /dev/null http://127.0.0.1/
    wget -q -O - "http://127.0.0.1/api/game/page?pageNo=1&pageSize=1" |
        grep -Eq '\''"code"[[:space:]]*:[[:space:]]*200([,}[:space:]])'\''
    if wget -q -O /dev/null http://127.0.0.1/api/internal/; then
        echo "Internal API unexpectedly reachable" >&2
        exit 1
    fi
'
echo "PASS frontend, Nginx /api, anonymous game query, internal route isolation"
