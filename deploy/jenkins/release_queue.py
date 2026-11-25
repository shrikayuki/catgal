"""连接普通 Jenkins 作业与固定的主机发布器。"""

import argparse
import os
import re
import shutil
import stat
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE if (HERE / "release_common.py").is_file() else HERE.parent / "linux"))
import release_common as release

SPOOL = Path("/var/lib/catgal-jenkins")
ENABLE = Path("/etc/catgal-jenkins/deploy-enabled")
MAX_ARCHIVE = 128 * 1024**2
MAX_SOURCE = 512 * 1024**2
REQUEST = re.compile(r"jenkins-([a-f0-9]{40})-[a-f0-9]{12}")
PINNED = ("deploy/linux/compose.yaml", "deploy/linux/smoke.sh")
REQUIRED = ("pom.xml", "gal-common/pom.xml", "gal-server/Dockerfile", "ai-service/Dockerfile",
            "ai-service/uv.lock", "frontend/Dockerfile", "frontend/package-lock.json")


def extract_sources(archive, destination):
    # 先验证整份清单，再写文件。工作流上传的归档不能提供私有配置或任意主机路径。
    entries = archive.infolist()
    names = {entry.filename for entry in entries}
    if len(entries) > 10000 or len(names) != len(entries) or sum(e.file_size for e in entries) > MAX_SOURCE:
        raise ValueError("Invalid source archive size or duplicate members")
    if not set(REQUIRED).issubset(names):
        raise ValueError("Missing required Catgal build inputs")
    for entry in entries:
        name = entry.filename
        mode = stat.S_IFMT(entry.external_attr >> 16)
        if (entry.is_dir() or mode not in (0, stat.S_IFREG) or name != PurePosixPath(name).as_posix()
                or not release.allowed_source(name)):
            raise ValueError("Unsafe source archive member")
    destination.mkdir(mode=0o700)
    for entry in entries:
        if entry.filename in PINNED:
            continue
        output = destination / entry.filename
        output.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(entry) as reader, output.open("xb") as writer:
            shutil.copyfileobj(reader, writer)


def submit(root, timeout):
    root = root.resolve()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    version = "jenkins-" + commit + "-" + uuid.uuid4().hex[:12]
    if not REQUEST.fullmatch(version):
        raise ValueError("Invalid checkout commit")
    # 只打包已提交的文件。Jenkins 工作区中的密钥、日志和依赖缓存不会被打包。
    raw = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "-z", "HEAD"], cwd=root)
    names = [name for name in raw.decode().split("\0") if release.allowed_source(name)]
    inbox = SPOOL / "incoming"
    temp = inbox / ("." + version + ".tmp")
    try:
        with temp.open("xb") as stream, zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
            for name in names:
                source = root / name
                if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(root):
                    raise ValueError("The checkout contains an unsupported build input")
                archive.write(source, name)
        if temp.stat().st_size > MAX_ARCHIVE:
            raise ValueError("Source archive exceeds its upload limit")
        temp.replace(inbox / (version + ".zip"))
    finally:
        temp.unlink(missing_ok=True)
    print(f"Catgal release: {version}", flush=True)
    deadline = time.monotonic() + timeout
    last_phase = None
    while time.monotonic() < deadline:
        # Jenkins 只读简要回执，详细部署阶段和日志保留在主机上。
        result = release.read_json(SPOOL / "status" / (version + ".json"))
        if result and result.get("phase") != last_phase:
            last_phase = result["phase"]
            print(f"{result['status']} {last_phase}", flush=True)
        if result and result["status"] in ("success", "failed"):
            return 0 if result["status"] == "success" else 1
        time.sleep(3)
    print(f"No terminal result before timeout. Check release {version} in the host journal.", file=sys.stderr)
    return 1


def process_request(path, jenkins_uid):
    import deploy_release as worker

    version = path.stem
    commit = REQUEST.fullmatch(version)[1]
    receipt = SPOOL / "status" / (version + ".json")
    verify_only = not ENABLE.is_file()
    release.write_json(receipt, {"status": "running", "phase": "accepted", "commit": commit,
                                 "verify_only": verify_only})
    destination = release.safe_release(release.APP_ROOT, version)
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as stream:
            file = os.fstat(stream.fileno())
            if not stat.S_ISREG(file.st_mode) or file.st_uid != jenkins_uid or file.st_size > MAX_ARCHIVE:
                raise ValueError("Invalid Jenkins request file")
            with zipfile.ZipFile(stream) as archive:
                extract_sources(archive, destination)
        # Compose 配置及冒烟脚本由 root 提前安装。作业只能提交应用构建输入。
        for name in PINNED:
            output = destination / name
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(HERE / Path(name).name, output)
        manifest = {"format": 2, "version": version, "builds": release.build_fingerprints(destination),
                    "compose_source": release.file_digest(destination / PINNED[0]),
                    "deployment_source": release.deployment_fingerprint(destination), "artifacts": {}}
        release.write_json(destination / release.MANIFEST, manifest, mode=0o600)
        worker.deploy(version, source_build=True, verify_only=verify_only)
    except Exception as error:
        print(f"{version}: {type(error).__name__}", flush=True)
        result = release.read_json(worker.control_dir() / "status" / (version + ".json"), {})
        phase = result.get("phase", "rejected-or-failed") if result.get("status") == "failed" else "rejected-or-failed"
        release.write_json(receipt, {"status": "failed", "phase": phase, "commit": commit, "verify_only": verify_only})
    else:
        phase = "verification-complete" if verify_only else "complete"
        release.write_json(receipt, {"status": "success", "phase": phase, "commit": commit, "verify_only": verify_only})


def watch():
    import pwd

    jenkins_uid = pwd.getpwnam("jenkins").pw_uid
    incoming = SPOOL / "incoming"
    processing = SPOOL / "processing"
    print("Catgal Jenkins release service ready", flush=True)
    while True:
        for path in sorted(incoming.glob("*.zip")):
            if not REQUEST.fullmatch(path.stem):
                continue
            target = processing / path.name
            path.replace(target)
            try:
                process_request(target, jenkins_uid)
            finally:
                if target.is_file() or target.is_symlink():
                    target.unlink()
        time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("submit", "watch"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--timeout", type=int, default=2400)
    args = parser.parse_args()
    if args.action == "watch":
        if os.geteuid() != 0:
            parser.error("Only the installed system service runs the watcher")
        watch()
    else:
        raise SystemExit(submit(args.root, args.timeout))


if __name__ == "__main__":
    main()
