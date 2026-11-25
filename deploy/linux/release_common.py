"""Release inputs and file validation shared by local publishing and the Linux runner."""

import hashlib
import json
import re
import subprocess
from pathlib import Path, PurePosixPath

SERVICES = ("java", "ai", "web")
APP_ROOT = Path("/home/shinku/catgal-app")
VERSION_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}")
MANIFEST = "release-manifest.json"
ROOT_FILES = {"pom.xml", ".dockerignore", ".gitattributes", ".gitignore", ".env.example"}
SOURCE_ROOTS = {"gal-common", "gal-server", "ai-service", "frontend", "deploy"}
EXCLUDED_PARTS = {".git", "target", "node_modules", ".venv", "__pycache__", ".pytest_cache",
                  ".ruff_cache", "secrets", "docs", "prompts", "dist"}


def validate_version(value):
    if not VERSION_PATTERN.fullmatch(value):
        raise ValueError("Release version must be a valid image tag of at most 80 characters")
    return value


def allowed_source(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or chr(92) in name:
        return False
    if not path.parts or (path.parts[0] not in SOURCE_ROOTS and name not in ROOT_FILES):
        return False
    if any(part in EXCLUDED_PARTS for part in path.parts):
        return False
    if name.startswith(("deploy/linux/artifacts/", "frontend/logs/", "frontend/temp/")):
        return False
    if any((part == ".env" or part.startswith(".env.")) and part != ".env.example" for part in path.parts):
        return False
    return path.name not in {".npmrc", ".pypirc"} and not name.lower().endswith((".md", ".log", ".zip", ".pyc", ".class"))


def source_files(root):
    root = root.resolve()
    if (root / ".git").exists():
        output = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root)
        candidates = output.decode("utf-8").split("\0")
    else:
        candidates = (path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file())
    names = []
    for name in candidates:
        if not allowed_source(name):
            continue
        path = root / name
        if path.is_file():
            if not path.resolve().is_relative_to(root):
                raise ValueError("Source path escapes the project")
            names.append(name)
    return sorted(set(names))


def service_for_source(name):
    if name == ".dockerignore":
        return SERVICES
    if name == "pom.xml" or name.startswith(("gal-common/", "gal-server/")) or name == "deploy/linux/HealthCheck.java":
        return ("java",)
    if name.startswith("frontend/") or name == "deploy/linux/nginx.conf":
        return ("web",)
    if name in {"ai-service/pyproject.toml", "ai-service/uv.lock", "ai-service/Dockerfile", "ai-service/.dockerignore"} or name.startswith(("ai-service/app/", "ai-service/tests/")):
        return ("ai",)
    return ()


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_fingerprints(root, names=None):
    digests = {service: hashlib.sha256() for service in SERVICES}
    for name in source_files(root) if names is None else names:
        value = file_digest(root / name)
        for service in service_for_source(name):
            digests[service].update((name + "\0" + value + "\n").encode())
    return {service: value.hexdigest() for service, value in digests.items()}


def json_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def deployment_fingerprint(root):
    inputs = {name: file_digest(root / name) for name in source_files(root)
              if PurePosixPath(name).parent.as_posix() == "deploy/linux"
              and PurePosixPath(name).suffix in {".py", ".ps1", ".sh"}}
    return json_digest(inputs)


def runtime_fingerprint(service):
    return json_digest({key: value for key, value in service.items() if key not in {"image", "build", "env_file"}})


def read_json(path, default=None):
    if not path.is_file():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value, mode=0o644):
    import os
    import uuid
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def safe_release(root, version):
    validate_version(version)
    path = (root / version).resolve()
    if path.parent != root.resolve():
        raise ValueError("Release path escapes application root")
    return path


def verify_manifest(root):
    manifest = read_json(root / MANIFEST)
    if not manifest or manifest.get("format") not in {1, 2} or manifest.get("version") != root.name:
        raise ValueError("Missing or incompatible release manifest")
    actual = build_fingerprints(root)
    if actual != manifest["builds"]:
        raise ValueError("Release source changed after validation")
    if manifest["format"] == 2 and (
            manifest.get("compose_source") != file_digest(root / "deploy/linux/compose.yaml")
            or manifest.get("deployment_source") != deployment_fingerprint(root)):
        raise ValueError("Deployment configuration changed after validation")
    for name, expected in manifest.get("artifacts", {}).items():
        path = (root / name).resolve()
        if not name.startswith("deploy/linux/artifacts/") or not path.is_relative_to(root.resolve()):
            raise ValueError("Invalid artifact path")
        if file_digest(path) != expected:
            raise ValueError("Release artifact checksum mismatch")
    return manifest
