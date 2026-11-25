#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$deploy_dir"

if [[ "${1:-}" == watch ]]; then
    exec python3 "$deploy_dir/deploy_release.py" watch
fi
if [[ "${1:-}" == deploy && "${2:-}" == auto ]]; then
    exec python3 "$deploy_dir/deploy_release.py" deploy "$(basename "$(cd ../.. && pwd)")"
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose v2.30+ is required. Send the version output to the developer." >&2
    exit 1
fi
if [[ ! -f .env || ! -f secrets/java.env || ! -f secrets/ai.env ]]; then
    echo "Upload .env, secrets/java.env and secrets/ai.env first." >&2
    exit 1
fi
chmod 700 secrets
chmod 600 .env secrets/java.env secrets/ai.env
compose=(docker compose --env-file .env -f compose.yaml)
# Quiet validation prevents resolved secrets from being printed.
"${compose[@]}" config --quiet

action="${1:-status}"
shift || true
case "$action" in
    check)
        docker info --format 'Docker {{.ServerVersion}}, architecture {{.Architecture}}'
        "${compose[@]}" config --services
        ;;
    deploy)
        services=("$@")
        if [[ ${#services[@]} == 0 ]]; then services=(java ai web); fi
        for service in "${services[@]}"; do
            case "$service" in
                java|ai|web) ;;
                *) echo "Build services must be java, ai or web." >&2; exit 1 ;;
            esac
        done
        # Snapshot the images of unchanged services before any container is replaced.
        declare -A reused_images=()
        for service in java ai web; do
            if [[ " ${services[*]} " == *" $service "* ]]; then continue; fi
            container_id="$("${compose[@]}" ps -q "$service")"
            if [[ -z "$container_id" ]]; then
                echo "No running $service image to reuse. Run deploy for a full build." >&2
                exit 1
            fi
            reused_images["$service"]="$(docker inspect --format '{{.Image}}' "$container_id")"
        done
        # Build sequentially to leave memory available for the existing ES and MySQL.
        for service in "${services[@]}"; do "${compose[@]}" build "$service"; done
        for service in "${!reused_images[@]}"; do
            target_image="$("${compose[@]}" config --images | grep "^catgal/$service:")"
            docker image tag "${reused_images[$service]}" "$target_image"
        done
        # DNS in Nginx upstreams must be refreshed after Java replacement.
        "${compose[@]}" up -d --no-build --force-recreate --wait --wait-timeout 240
        bash "$deploy_dir/smoke.sh"
        ;;
    restore)
        [[ $# == 1 ]] || { echo "Usage: bash manage.sh restore <existing-image-tag>" >&2; exit 1; }
        export APP_VERSION="$1"
        for service in java ai web; do
            docker image inspect "catgal/$service:$APP_VERSION" >/dev/null
        done
        "${compose[@]}" up -d --no-build --pull never --force-recreate --wait --wait-timeout 240
        bash "$deploy_dir/smoke.sh"
        ;;
    status) "${compose[@]}" ps ;;
    logs) "${compose[@]}" logs --tail 150 -f "$@" ;;
    stop) "${compose[@]}" stop ;;
    *) echo "Usage: bash manage.sh {check|deploy [java ai web]|restore <tag>|status|logs [service]|stop}" >&2; exit 1 ;;
esac
