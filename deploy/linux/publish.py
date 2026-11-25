"""Validate local changes, upload one immutable release and wait for the Linux watcher."""

import argparse
import base64
import io
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime
from pathlib import Path

from release_common import (
    APP_ROOT,
    MANIFEST,
    SERVICES,
    build_fingerprints,
    deployment_fingerprint,
    file_digest,
    source_files,
    validate_version,
)

ROOT = Path(__file__).resolve().parents[2]


def ssh(code, payload=b""):
    home = Path.home() / ".ssh"
    git_client = Path("C:/Program Files/Git/usr/bin/ssh.exe")
    client = str(git_client) if git_client.is_file() else shutil.which("ssh")
    if not client or not (home / "codex_catgal_vm_ed25519").is_file() or not (home / "known_hosts").is_file():
        raise RuntimeError("The existing dedicated SSH key and verified known_hosts are required")
    encoded = base64.b64encode(code.encode()).decode()
    remote = "import base64;exec(base64.b64decode('" + encoded + "'))"
    result = subprocess.run([client, "-i", str(home / "codex_catgal_vm_ed25519"), "-o", "BatchMode=yes",
                             "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10",
                             "-o", "UserKnownHostsFile=" + str(home / "known_hosts"),
                             "shinku@192.168.150.104", "python3 -c " + shlex.quote(remote)],
                            input=payload, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError("SSH operation failed. Check the VM connection and the existing SSH key.")
    return json.loads(result.stdout)


def remote_json(name):
    path = (APP_ROOT / ".deploy" / name).as_posix()
    return ssh("import json\nfrom pathlib import Path\np=Path(" + repr(path) + ")\n"
               "print(p.read_text() if p.is_file() else 'null')")


def executable(name, candidates=()):
    found = shutil.which(name)
    if found:
        return found
    for candidate in candidates:
        if Path(candidate).is_file():
            return str(candidate)
    raise RuntimeError("Executable not found: " + name)


def command(arguments, cwd):
    print("Running " + Path(arguments[0]).name + " " + " ".join(arguments[1:3]), flush=True)
    subprocess.run(arguments, cwd=cwd, check=True)


def build_java(output):
    maven = executable("mvn.cmd" if os.name == "nt" else "mvn",
                       ["C:/apache-maven-3.9.11/bin/mvn.cmd"])
    command([maven, "-B", "-ntp", "-Pdeployment", "-Dmaven.repo.local=" + str(Path.home() / ".m2/repository"),
             "package"], ROOT)
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    module = ET.parse(ROOT / "gal-server/pom.xml").getroot()
    project = ET.parse(ROOT / "pom.xml").getroot()
    artifact = module.findtext("m:artifactId", namespaces=namespace)
    version = module.findtext("m:version", namespaces=namespace) or project.findtext("m:version", namespaces=namespace)
    jar = ROOT / "gal-server/target/deployment" / f"{artifact}-{version}.jar"
    output.mkdir(parents=True)
    shutil.copyfile(jar, output / "app.jar")
    java_home = Path(os.environ.get("JAVA_HOME", ""))
    compiler = executable("javac.exe" if os.name == "nt" else "javac",
                          [java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")])
    command([compiler, "--release", "17", "-d", str(output / "health"),
             str(ROOT / "deploy/linux/HealthCheck.java")], ROOT)


def build_web(output):
    npm = executable("npm.cmd" if os.name == "nt" else "npm")
    frontend = output.parent.parent / "web-build"
    # Keep npm ci isolated from the running Vite development server.
    for name in source_files(ROOT):
        if name.startswith("frontend/"):
            destination = frontend / name.removeprefix("frontend/")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
    # npm ci's package cache supports reusing downloads after lockfile changes.
    command([npm, "ci", "--prefer-offline", "--no-audit", "--no-fund"], frontend)
    command([npm, "test"], frontend)
    command([npm, "run", "build"], frontend)
    shutil.copytree(frontend / "dist", output)


def test_ai():
    python = ROOT / "ai-service/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not python.is_file():
        raise RuntimeError("Prepare the existing AI development virtual environment before publishing AI changes")
    command([str(python), "-m", "pytest"], ROOT / "ai-service")
    command([str(python), "-m", "ruff", "check", "app", "tests"], ROOT / "ai-service")


def archive_release(version, names, fingerprints, artifact_root, bootstrap=False, validated=None):
    data = io.BytesIO()
    artifacts = {}
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            archive.write(ROOT / name, name)
        if artifact_root.is_dir():
            for path in sorted(artifact_root.rglob("*")):
                if path.is_file():
                    name = "deploy/linux/artifacts/" + path.relative_to(artifact_root).as_posix()
                    if not path.resolve().is_relative_to(artifact_root.resolve()):
                        raise ValueError("Artifact path escapes build directory")
                    artifacts[name] = file_digest(path)
                    compression = zipfile.ZIP_STORED if path.suffix == ".jar" else zipfile.ZIP_DEFLATED
                    archive.write(path, name, compress_type=compression)
        if not bootstrap:
            metadata = validated or {"compose_source": file_digest(ROOT / "deploy/linux/compose.yaml"),
                                     "deployment_source": deployment_fingerprint(ROOT)}
            archive.writestr(MANIFEST, json.dumps({"format": 2, "version": version,
                                                 "builds": fingerprints, "artifacts": artifacts, **metadata}))
    return data.getvalue()


def upload(version, payload, bootstrap):
    code = """
import io,json,os,stat,sys,zipfile
from pathlib import Path,PurePosixPath
os.umask(0o077)
root=Path(APP_ROOT)
release=root/VERSION
archive=zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))
for entry in archive.infolist():
    path=PurePosixPath(entry.filename)
    if path.is_absolute() or '..' in path.parts or chr(92) in entry.filename or stat.S_ISLNK(entry.external_attr >> 16):
        raise SystemExit('Unsafe release archive')
root.mkdir(parents=True,exist_ok=True)
release.mkdir()
archive.extractall(release)
control=root/'.deploy'
(control/'requests').mkdir(parents=True,exist_ok=True)
if not BOOTSTRAP:
    temporary=control/'requests'/(VERSION+'.tmp')
    temporary.write_text(json.dumps({'version':VERSION}))
    os.replace(temporary,control/'requests'/(VERSION+'.json'))
print(json.dumps({'release':str(release),'queued':not BOOTSTRAP}))
"""
    code = "APP_ROOT=" + repr(APP_ROOT.as_posix()) + "\nVERSION=" + repr(version) + "\nBOOTSTRAP=" + repr(bootstrap) + "\n" + code
    return ssh(code, payload)


def retry(version):
    validate_version(version)
    code = """
import json,os
from pathlib import Path
root=Path(APP_ROOT)
if not (root/VERSION/'release-manifest.json').is_file(): raise SystemExit('Release missing')
requests=root/'.deploy/requests'
requests.mkdir(parents=True,exist_ok=True)
temporary=requests/(VERSION+'.tmp')
temporary.write_text(json.dumps({'version':VERSION}))
os.replace(temporary,requests/(VERSION+'.json'))
print(json.dumps({'queued':True}))
"""
    return ssh("APP_ROOT=" + repr(APP_ROOT.as_posix()) + "\nVERSION=" + repr(version) + "\n" + code)


def wait_for_release(version, timeout):
    deadline = time.monotonic() + timeout
    previous = None
    while time.monotonic() < deadline:
        result = remote_json("status/" + version + ".json")
        summary = (result or {}).get("phase", "queued-awaiting-root-watcher")
        if summary != previous:
            print(version + " " + summary, flush=True)
            previous = summary
        if result and result["status"] in {"success", "failed"}:
            if result["status"] == "failed":
                raise RuntimeError("Deployment failed: " + summary + ". Review root Xshell logs, then use --retry " + version)
            return result
        time.sleep(3)
    raise RuntimeError("Release is still pending. Check the root watcher and use --status " + version)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="linux-" + datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:4])
    parser.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--storage-bootstrap", action="store_true")
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--no-wait", action="store_true")
    parser.add_argument("--retry")
    parser.add_argument("--status")
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    if args.status:
        validate_version(args.status)
        print(json.dumps(remote_json("status/" + args.status + ".json"), ensure_ascii=False, indent=2))
        return
    if args.retry:
        retry(args.retry)
        if not args.no_wait:
            wait_for_release(args.retry, args.timeout)
        return
    version = validate_version(args.version)
    if args.storage_bootstrap:
        from storage_release import STORAGE_INPUTS, storage_manifest
        manifest = storage_manifest(ROOT, version)
        if args.plan:
            print("Storage bootstrap inputs verified. MinIO is managed independently from application images.")
            return
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
            for name in STORAGE_INPUTS:
                archive.write(ROOT / name, name)
            archive.writestr(MANIFEST, json.dumps(manifest))
        # The normal uploader queues a fixed release directory consumed by the root watcher.
        print(json.dumps(upload(version, data.getvalue(), False)), flush=True)
        if not args.no_wait:
            print(json.dumps(wait_for_release(version, args.timeout), ensure_ascii=False))
        return
    names = source_files(ROOT)
    fingerprints = build_fingerprints(ROOT, names)
    deployment_inputs = deployment_fingerprint(ROOT)
    compose_input = file_digest(ROOT / "deploy/linux/compose.yaml")
    baseline = None if args.bootstrap else remote_json("state.json")
    if not args.bootstrap and not baseline:
        raise RuntimeError("Start the root deployment watcher once. Use --bootstrap to upload its scripts first.")
    builds = [] if args.bootstrap else [name for name in SERVICES if
                                       baseline["services"][name]["build"] != fingerprints[name]]
    configuration_changed = bool(baseline and (baseline.get("compose_source") != file_digest(ROOT / "deploy/linux/compose.yaml")
                                 or baseline.get("deployment_source") != deployment_fingerprint(ROOT)))
    print("Image builds: " + (", ".join(builds) or "none"), flush=True)
    if args.plan:
        print("Compose configuration changed: " + str(configuration_changed))
        return
    if not args.bootstrap and not builds and not configuration_changed:
        print("Application inputs match the successful Linux release. No update needed.")
        return
    stage = ROOT / "target/linux" / version
    stage.mkdir(parents=True, exist_ok=False)
    artifacts = stage / "artifacts"
    if "java" in builds:
        build_java(artifacts / "java")
    if "web" in builds:
        build_web(artifacts / "web")
    if "ai" in builds:
        test_ai()
    if (fingerprints != build_fingerprints(ROOT, source_files(ROOT))
            or deployment_inputs != deployment_fingerprint(ROOT)
            or compose_input != file_digest(ROOT / "deploy/linux/compose.yaml")):
        raise RuntimeError("Workspace source changed during validation. Publish a fresh version.")
    payload = archive_release(version, names, fingerprints, artifacts, args.bootstrap,
                              {"compose_source": compose_input, "deployment_source": deployment_inputs})
    (stage / "source.zip").write_bytes(payload)
    print(json.dumps(upload(version, payload, args.bootstrap), ensure_ascii=False), flush=True)
    if args.bootstrap:
        print("Run once in root Xshell: cd " + (APP_ROOT / version / "deploy/linux").as_posix() + " && bash manage.sh watch")
    elif not args.no_wait:
        print(json.dumps(wait_for_release(version, args.timeout), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
