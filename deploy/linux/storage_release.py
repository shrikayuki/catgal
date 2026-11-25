"""Fixed MinIO bootstrap inputs accepted by the existing root deployment watcher."""
import json
from release_common import file_digest

STORAGE_INPUTS = (
    "deploy/minio/Dockerfile", "deploy/minio/.dockerignore", "deploy/minio/compose.yaml",
    "deploy/minio/manage.sh", "deploy/minio/initialize.sh", "deploy/linux/deploy_release.py",
    "deploy/linux/release_common.py", "deploy/linux/storage_release.py",
)


def storage_manifest(root, version):
    return {"kind": "minio", "version": version,
            "files": {name: file_digest(root / name) for name in STORAGE_INPUTS}}


def verify_storage_manifest(root, version):
    manifest = json.loads((root / "release-manifest.json").read_text())
    if manifest.get("kind") != "minio" or manifest.get("version") != version:
        raise ValueError("Storage release identity mismatch")
    if set(manifest.get("files", {})) != set(STORAGE_INPUTS):
        raise ValueError("Unexpected storage bootstrap inputs")
    for name, expected in manifest["files"].items():
        path = (root / name).resolve()
        if not path.is_relative_to(root.resolve()) or file_digest(path) != expected:
            raise ValueError("Storage bootstrap checksum mismatch")
    return manifest


def initialize_application_storage(environment_path, storage_environment):
    """Initialize the approved backend once, preserving subsequent operator choices."""
    settings = dict(line.split("=", 1) for line in environment_path.read_text().splitlines() if "=" in line)
    if "STORAGE_ENABLED" in settings:
        return
    storage = dict(line.split("=", 1) for line in storage_environment.read_text().splitlines() if "=" in line)
    for key in ("MINIO_APP_ACCESS_KEY", "MINIO_APP_SECRET_KEY", "MINIO_BUCKET"):
        if not storage.get(key):
            raise ValueError("Missing private MinIO application setting")
    settings.update(STORAGE_ENABLED="true", MINIO_ENDPOINT="http://host.docker.internal:9000",
                    MINIO_ACCESS_KEY=storage["MINIO_APP_ACCESS_KEY"], MINIO_SECRET_KEY=storage["MINIO_APP_SECRET_KEY"],
                    MINIO_BUCKET=storage["MINIO_BUCKET"])
    environment_path.write_text("".join(name + "=" + value + "\n" for name, value in settings.items()))
    environment_path.chmod(0o600)


def storage_action(root, previous):
    image_inputs = ("deploy/minio/Dockerfile", "deploy/minio/compose.yaml")
    if previous and all((previous / name).is_file() and file_digest(previous / name) == file_digest(root / name)
                        for name in image_inputs):
        return "initialize"
    return "deploy"
