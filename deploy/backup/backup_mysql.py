"""对 Catgal MySQL 生成可校验的逻辑备份，支持 Windows 和 Linux。"""

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def private_directory(parent, prefix):
    directory = Path(tempfile.mkdtemp(prefix=prefix, dir=parent))
    if os.name == "nt":
        identity = subprocess.check_output(["whoami.exe", "/user", "/fo", "csv", "/nh"])
        sid = re.search(rb"S-\d+(?:-\d+)+", identity)
        if sid is None:
            raise RuntimeError("无法确定当前 Windows 账号 SID")
        # Windows 下显式去除继承 ACL，随后创建的备份和日志由当前账号独占读取。
        subprocess.run(["icacls.exe", str(directory), "/inheritance:r", "/grant:r", f"*{sid[0].decode()}:(OI)(CI)F"],
                       check=True, capture_output=True)
    return directory


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def backup(client, options_file, database, output, schema_only=False):
    if not re.fullmatch(r"[A-Za-z0-9_]+", database):
        raise ValueError("数据库名称只能包含字母、数字和下划线")
    if not options_file.is_file():
        raise ValueError("找不到指定的 MySQL 客户端配置文件")
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    now = datetime.now(timezone.utc)
    directory = private_directory(output, f"{database}-{now:%Y%m%dT%H%M%SZ}-")
    partial = directory / "backup.sql.gz.partial"
    error_log = directory / "mysqldump.stderr.log"
    command = [client, f"--defaults-file={options_file.resolve()}", "--single-transaction", "--quick",
               "--skip-lock-tables", "--skip-add-locks", "--skip-add-drop-table", "--set-gtid-purged=OFF",
               "--no-tablespaces", "--column-statistics=0", "--hex-blob", "--triggers", "--routines",
               "--events", "--default-character-set=utf8mb4"]
    if schema_only:
        command.append("--no-data")
    command.append(database)
    try:
        with error_log.open("wb") as errors:
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors) as process:
                try:
                    with gzip.open(partial, "xb") as compressed:
                        shutil.copyfileobj(process.stdout, compressed)
                except BaseException:
                    process.kill()
                    raise
                if process.wait():
                    raise RuntimeError(f"mysqldump 失败，请检查私有日志 {error_log}")

        # 最后才写入完成清单。半成品即使有 gzip 文件也不会通过完整性验证。
        archive = directory / "backup.sql.gz"
        partial.rename(archive)
        manifest = {"database": database, "kind": "schema" if schema_only else "full",
                    "startedAt": now.isoformat(), "bytes": archive.stat().st_size,
                    "sha256": digest(archive)}
        pending_manifest = directory / "manifest.json.partial"
        pending_manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        pending_manifest.rename(directory / "manifest.json")
        return directory
    finally:
        partial.unlink(missing_ok=True)


def verify(directory):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    archive = directory / "backup.sql.gz"
    if archive.stat().st_size != manifest["bytes"] or digest(archive) != manifest["sha256"]:
        raise ValueError("备份大小或 SHA-256 校验不符")
    with gzip.open(archive, "rb") as source:
        if not source.read(1):
            raise ValueError("备份中没有 SQL 内容")
        while source.read(1024 * 1024):
            # 读到结尾时 gzip 模块校验数据流和 CRC。
            pass
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create", help="导出并压缩一个 MySQL 数据库")
    create.add_argument("--defaults-file", type=Path, required=True)
    create.add_argument("--output-dir", type=Path, required=True)
    create.add_argument("--database", default="catgal")
    create.add_argument("--mysqldump", default="mysqldump")
    create.add_argument("--schema-only", action="store_true", help="仅导出结构与触发器等定义")
    check = commands.add_parser("verify", help="检验 SHA-256 与 gzip 数据完整性")
    check.add_argument("backup_dir", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "create":
            print(backup(args.mysqldump, args.defaults_file, args.database, args.output_dir, args.schema_only))
        else:
            print(json.dumps(verify(args.backup_dir)))
    except (KeyError, OSError, EOFError, ValueError, RuntimeError, subprocess.CalledProcessError) as failure:
        print(str(failure), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
