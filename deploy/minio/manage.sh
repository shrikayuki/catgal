#!/usr/bin/env bash
set -euo pipefail
deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$deploy_dir"
[[ -f secrets/minio.env ]] || { echo 'Prepare the private MinIO environment first.' >&2; exit 1; }
chmod 700 secrets
chmod 600 secrets/minio.env
compose=(docker compose -p catgal-storage -f "$deploy_dir/compose.yaml")
"${compose[@]}" config --quiet
case "${1:-status}" in
    deploy)
        data_dir=/home/shinku/catgal-storage/data
        [[ "$(realpath -m -- "$data_dir")" == /home/shinku/catgal-storage/data ]] || exit 1
        install -d -m 0750 -o 1000 -g 1000 "$data_dir"
        "${compose[@]}" build minio
        "${compose[@]}" up -d --no-build --wait --wait-timeout 180 minio
        "${compose[@]}" --profile setup run --rm --no-deps initialize
        echo 'PASS MinIO health, private bucket and application policy initialization'
        ;;
    initialize)
        "${compose[@]}" --profile setup run --rm --no-deps --pull never initialize
        echo 'PASS MinIO application policy update'
        ;;
    status) "${compose[@]}" ps ;;
    logs) "${compose[@]}" logs --tail 80 minio ;;
    *) echo 'Usage: bash manage.sh {deploy|initialize|status|logs}' >&2; exit 1 ;;
esac
