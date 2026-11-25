"""Linux release worker. Uses the current Docker project as the initial baseline."""

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from release_common import (
    APP_ROOT,
    SERVICES,
    build_fingerprints,
    deployment_fingerprint,
    file_digest,
    read_json,
    runtime_fingerprint,
    safe_release,
    source_files,
    validate_version,
    verify_manifest,
    write_json,
)


def control_dir():
    return APP_ROOT / ".deploy"


@contextmanager
def exclusive_lock(name):
    import fcntl
    control_dir().mkdir(parents=True, exist_ok=True, mode=0o700)
    with (control_dir() / name).open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def run(arguments, root=None, capture=False, log=None):
    environment = os.environ.copy()
    if root:
        # Let the selected release's .env supply Compose settings.
        for line in (root / "deploy/linux/.env").read_text().splitlines():
            name, separator, _ = line.partition("=")
            if separator:
                environment.pop(name, None)
    result = subprocess.run(arguments, cwd=root, env=environment, text=True,
                            stdout=subprocess.PIPE if capture else log,
                            stderr=log if log else subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError(f"Command failed with exit {result.returncode}. Review the private deployment log.")
    return result.stdout if capture else ""


def compose(root, *arguments, log=None, capture=False, override=None):
    command = ["docker", "compose", "--env-file", str(root / "deploy/linux/.env"),
               "-f", str(root / "deploy/linux/compose.yaml")]
    if override:
        command.extend(["-f", str(override)])
    return run([*command, *arguments], root=root, log=log, capture=capture)


def running_services(log=None):
    records = {}
    for service in SERVICES:
        ids = run(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=catgal-app",
                   "--filter", "label=com.docker.compose.service=" + service], capture=True, log=log).split()
        if len(ids) != 1:
            raise RuntimeError(f"Expected one running {service} container. Finish the existing deployment first.")
        data = json.loads(run(["docker", "inspect", ids[0]], capture=True, log=log))[0]
        directory = Path(data["Config"]["Labels"]["com.docker.compose.project.working_dir"])
        origin = directory.resolve().parent.parent
        if origin.parent != APP_ROOT.resolve() or not (origin / "deploy/linux/compose.yaml").is_file():
            raise RuntimeError("Running service does not belong to the expected release directory")
        records[service] = {"container": ids[0], "image": data["Image"], "origin": str(origin),
                            "image_ref": data["Config"]["Image"],
                            "health": data.get("State", {}).get("Health", {}).get("Status", "unknown"),
                            "image_build": data["Config"]["Labels"].get("com.catgal.build-fingerprint", "")}
    return records


def compose_services(root, log=None):
    return json.loads(compose(root, "config", "--format", "json", capture=True, log=log))["services"]


def load_baseline(log=None):
    current = running_services(log)
    if any(record["health"] != "healthy" for record in current.values()):
        raise RuntimeError("Existing containers are unhealthy. Recover the last successful release before publishing.")
    previous = read_json(control_dir() / "state.json")
    if previous and previous.get("format") == 1 and all(
            previous["services"][name]["image"] == current[name]["image"]
            and previous["services"][name]["origin"] == current[name]["origin"] for name in SERVICES):
        return previous, current
    # Existing hand-deployed containers can seed the first automated release.
    services = {}
    for name, record in current.items():
        root = Path(record["origin"])
        fingerprint = record["image_build"]
        if len(fingerprint) != 64:
            settings = dict(line.split("=", 1) for line in (root / "deploy/linux/.env").read_text().splitlines()
                            if "=" in line and not line.startswith("#"))
            fingerprint = "unknown"
            if record["image_ref"] == "catgal/" + name + ":" + settings.get("APP_VERSION", ""):
                fingerprint = build_fingerprints(root)[name]
        services[name] = {**record, "build": fingerprint,
                          "runtime": runtime_fingerprint(compose_services(root, log)[name])}
    root = Path(current["java"]["origin"])
    previous = {"format": 1, "release": str(root), "services": services,
                "compose_source": file_digest(root / "deploy/linux/compose.yaml"),
                "deployment_source": deployment_fingerprint(root)}
    write_json(control_dir() / "state.json", previous)
    return previous, current


def prepare_environment(root, previous, fingerprints, source_build=False):
    source = Path(previous["release"]) / "deploy/linux"
    destination = root / "deploy/linux"
    secrets = destination / "secrets"
    secrets.mkdir(mode=0o700, exist_ok=True)
    secrets.chmod(0o700)
    for name in ("java.env", "ai.env"):
        if (source / "secrets" / name).resolve() != (secrets / name).resolve():
            shutil.copyfile(source / "secrets" / name, secrets / name)
        (secrets / name).chmod(0o600)
    if (not source_build and (control_dir() / "storage-state.json").is_file()
            and (root / "gal-server/src/main/java/com/catgal/server/storage/StorageProperties.java").is_file()):
        from storage_release import initialize_application_storage
        initialize_application_storage(secrets / "java.env", control_dir() / "storage-secrets/minio.env")
    settings = {}
    for line in (source / ".env").read_text().splitlines():
        name, separator, value = line.partition("=")
        if separator:
            settings[name] = value
    settings.update(APP_VERSION=root.name,
                    JAVA_DOCKERFILE="gal-server/Dockerfile" if source_build else "gal-server/Dockerfile.prebuilt",
                    WEB_DOCKERFILE="frontend/Dockerfile" if source_build else "frontend/Dockerfile.prebuilt")
    settings.update({name.upper() + "_BUILD_FINGERPRINT": value for name, value in fingerprints.items()})
    path = destination / ".env"
    path.write_text("".join(f"{name}={value}\n" for name, value in settings.items()))
    path.chmod(0o600)


def plan_release(previous, manifest, configuration):
    builds = [name for name in SERVICES if previous["services"][name]["build"] != manifest["builds"][name]]
    updates = [name for name in SERVICES if name in builds or
               previous["services"][name]["runtime"] != runtime_fingerprint(configuration[name])]
    return builds, updates


def require_artifacts(root, manifest, builds):
    required = []
    if "java" in builds:
        required.extend(["deploy/linux/artifacts/java/app.jar", "deploy/linux/artifacts/java/health/HealthCheck.class"])
    if "web" in builds:
        required.append("deploy/linux/artifacts/web/index.html")
    if any(name not in manifest.get("artifacts", {}) or not (root / name).is_file() for name in required):
        raise RuntimeError("Required build artifacts are missing. Publish again against the current baseline.")


def build_source(root, name, fingerprint, log):
    # 构建上下文只含验证后的源码。运行配置与密钥即使在同一个发布目录，也不会进入镜像构建。
    files = source_files(root)
    with tempfile.TemporaryDirectory(prefix="catgal-build-") as directory:
        build_root = Path(directory)
        for filename in files:
            output = build_root / filename
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / filename, output)
        context = build_root / "ai-service" if name == "ai" else build_root
        dockerfile = {"java": "gal-server/Dockerfile", "ai": "ai-service/Dockerfile", "web": "frontend/Dockerfile"}[name]
        if name == "ai":
            run(["docker", "build", "--target", "test", str(context)], log=log)
        run(["docker", "build", "-f", str(build_root / dockerfile), "-t", f"catgal/{name}:{root.name}",
             "--label", f"com.catgal.build-fingerprint={fingerprint}", str(context)], log=log)


def status(version, phase, state="running", **details):
    value = {"version": version, "status": state, "phase": phase,
             "updated_at": datetime.now(UTC).isoformat(), **details}
    write_json(control_dir() / "status" / (version + ".json"), value)
    print(f"{version} {state} {phase}", flush=True)


def reload_nginx(log):
    container = running_services(log)["web"]["container"]
    run(["docker", "exec", container, "nginx", "-t"], log=log)
    run(["docker", "exec", container, "nginx", "-s", "reload"], log=log)


def smoke(root, log):
    for attempt in range(6):
        try:
            run(["bash", str(root / "deploy/linux/smoke.sh")], root=root, log=log)
            return
        except RuntimeError:
            if attempt == 5:
                raise
            time.sleep(2)


def restore_snapshot(snapshot, updates, log, directory):
    for name in updates:
        record = snapshot[name]
        tag = "catgal/" + name + ":rollback-" + directory.name
        run(["docker", "image", "tag", record["image"], tag], log=log)
        override = directory / (name + "-rollback.json")
        write_json(override, {"services": {name: {"image": tag}}}, mode=0o600)
        compose(Path(record["origin"]), "up", "-d", "--no-build", "--no-deps", "--pull", "never",
                "--force-recreate", "--wait", "--wait-timeout", "240", name, log=log, override=override)
    if "java" in updates:
        reload_nginx(log)
    # The selected images may originate from different partial releases.
    smoke(Path(snapshot["web"]["origin"]), log)


def deploy(version, *, source_build=False, verify_only=False):
    validate_version(version)
    root = safe_release(APP_ROOT, version)
    if not source_build and read_json(root / "release-manifest.json", {}).get("kind") == "minio":
        deploy_storage(root, version)
        return
    manifest = verify_manifest(root)
    log_directory = control_dir() / "logs" / version
    log_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    log_directory.chmod(0o700)
    log_path = log_directory / "deploy.log"
    with exclusive_lock("deployment.lock"), log_path.open("a") as log:
        log_path.chmod(0o600)
        updates = []
        builds = []
        replacement_started = False
        previous = None
        snapshot = None
        try:
            status(version, "planning")
            previous, snapshot = load_baseline(log)
            if any(record["health"] != "healthy" for record in snapshot.values()):
                raise RuntimeError("Existing application containers must be healthy before updating")
            prepare_environment(root, previous, manifest["builds"], source_build=source_build)
            configuration = compose_services(root, log)
            builds, updates = plan_release(previous, manifest, configuration)
            if not source_build:
                require_artifacts(root, manifest, builds)
            for name in builds:
                status(version, "building-" + name, builds=builds, updates=updates)
                if source_build:
                    build_source(root, name, manifest["builds"][name], log)
                else:
                    compose(root, "build", name, log=log)
            if verify_only:
                # 试运行只构建并测试候选镜像。现有容器及已通过的发布指针继续提供服务。
                status(version, "verification-complete", "success", builds=builds, updates=[])
                return
            # Detect an overlapping manual deployment before replacing any service.
            observed = running_services(log)
            if any(observed[name]["container"] != snapshot[name]["container"] for name in SERVICES):
                raise RuntimeError("Running containers changed during this build. Retry after the other deployment finishes.")
            for name in SERVICES:
                if name not in builds:
                    run(["docker", "image", "tag", snapshot[name]["image"], f"catgal/{name}:{version}"], log=log)
            if updates:
                status(version, "updating", builds=builds, updates=updates)
                replacement_started = True
                compose(root, "up", "-d", "--no-build", "--no-deps", "--pull", "never", "--force-recreate",
                        "--wait", "--wait-timeout", "240", *updates, log=log)
                if "java" in updates and "web" not in updates:
                    reload_nginx(log)
            status(version, "health-check", builds=builds, updates=updates)
            smoke(root, log)
            current = running_services(log)
            if any(record["health"] != "healthy" for record in current.values()):
                raise RuntimeError("Application health check failed")
            state = {"format": 1, "release": str(root),
                     "compose_source": file_digest(root / "deploy/linux/compose.yaml"),
                     "deployment_source": deployment_fingerprint(root), "services": {}}
            for name in SERVICES:
                state["services"][name] = {**current[name], "build": manifest["builds"][name],
                                           "runtime": runtime_fingerprint(configuration[name])}
            write_json(control_dir() / "previous.json", previous)
            write_json(control_dir() / "state.json", state)
            status(version, "complete", "success", builds=builds, updates=updates)
        except Exception:
            if replacement_started:
                status(version, "rolling-back", updates=updates)
                try:
                    restore_snapshot(snapshot, updates, log, log_directory)
                except Exception:
                    status(version, "rollback-failed", "failed", log=str(log_path), builds=builds, updates=updates)
                    raise
            status(version, "failed-before-update" if not replacement_started else "rolled-back",
                   "failed", log=str(log_path), builds=builds, updates=updates)
            raise


def deploy_storage(root, version):
    from storage_release import verify_storage_manifest, storage_action
    verify_storage_manifest(root, version)
    log_directory = control_dir() / "logs" / version
    log_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    log_directory.chmod(0o700)
    secret_source = control_dir() / "storage-secrets/minio.env"
    if not secret_source.is_file():
        raise RuntimeError("Prepare the private MinIO environment before deploying storage")
    destination = root / "deploy/minio/secrets"
    destination.mkdir(mode=0o700, exist_ok=True)
    destination.chmod(0o700)
    shutil.copyfile(secret_source, destination / "minio.env")
    (destination / "minio.env").chmod(0o600)
    log_path = log_directory / "deploy.log"
    with exclusive_lock("deployment.lock"), log_path.open("a") as log:
        log_path.chmod(0o600)
        try:
            previous = read_json(control_dir() / "storage-state.json", {})
            previous_root = safe_release(APP_ROOT, Path(previous["release"]).name) if previous else None
            action = storage_action(root, previous_root)
            status(version, "updating-minio-policy" if action == "initialize" else "building-minio")
            run(["bash", str(root / "deploy/minio/manage.sh"), action], log=log)
            write_json(control_dir() / "storage-state.json", {"release": str(root), "version": version})
            status(version, "complete", "success", builds=[] if action == "initialize" else ["minio"], updates=["minio"])
        except Exception:
            # Share a bounded diagnostic after removing every configured credential value.
            log.flush()
            diagnostic = log_path.read_text(errors="replace")[-2500:]
            for line in secret_source.read_text().splitlines():
                _, separator, value = line.partition("=")
                if separator and value:
                    diagnostic = diagnostic.replace(value, "[redacted]")
            status(version, "storage-deploy-failed", "failed", diagnostic=diagnostic)
            raise


def watch():
    for name in ("requests", "running", "status"):
        (control_dir() / name).mkdir(parents=True, exist_ok=True)
    with exclusive_lock("watcher.lock"):
        load_baseline()
        print("Catgal deployment watcher ready. Keep this Xshell session running.", flush=True)
        while True:
            requests = sorted((control_dir() / "running").glob("*.json"))
            requests += sorted((control_dir() / "requests").glob("*.json"))
            for request in requests:
                running = control_dir() / "running" / request.name
                version = None
                try:
                    version = validate_version(request.stem)
                    if request != running:
                        os.replace(request, running)
                    completed = read_json(control_dir() / "status" / (version + ".json"), {})
                    if completed.get("status") != "success":
                        worker = safe_release(APP_ROOT, version) / "deploy/linux/deploy_release.py"
                        run(["python3", str(worker), "deploy", version])
                except Exception as error:  # noqa: BLE001 -- keep the watcher available after an isolated failed release.
                    if version:
                        result = read_json(control_dir() / "status" / (version + ".json"), {})
                        if result.get("status") != "failed":
                            status(version, "validation-failed", "failed")
                    print(f"Deployment failed ({type(error).__name__}). Check .deploy/status and private logs.", flush=True)
                finally:
                    running.unlink(missing_ok=True)
            time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("watch", "deploy"))
    parser.add_argument("version", nargs="?")
    args = parser.parse_args()
    if getattr(os, "geteuid", lambda: -1)() != 0:
        parser.error("Run this command in the existing root Xshell")
    if args.action == "watch":
        watch()
    elif args.version:
        deploy(args.version)
    else:
        parser.error("deploy requires a release version")


if __name__ == "__main__":
    main()
