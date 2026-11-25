"""Upload prepared source and private configuration over the existing SSH key."""

import argparse
import io
import re
import shlex
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}', args.version):
        parser.error('Invalid image version')

    root = Path(__file__).resolve().parents[2]
    key = Path.home() / '.ssh/codex_catgal_vm_ed25519'
    known_hosts = Path.home() / '.ssh/known_hosts'
    git_ssh = Path('C:/Program Files/Git/usr/bin/ssh.exe')
    ssh = str(git_ssh) if git_ssh.exists() else shutil.which('ssh')
    if not ssh or not key.is_file() or not known_hosts.is_file():
        parser.error('Existing SSH client, dedicated key and verified known_hosts are required')

    payload = io.BytesIO()
    names = set()
    with zipfile.ZipFile(payload, 'w', zipfile.ZIP_DEFLATED) as combined:
        for kind in ('source', 'private'):
            archive_path = root / f'target/linux/catgal-{kind}-{args.version}.zip'
            with zipfile.ZipFile(archive_path) as archive:
                for entry in archive.infolist():
                    path = PurePosixPath(entry.filename)
                    if path.is_absolute() or '..' in path.parts or '\\' in entry.filename:
                        parser.error('Unsafe archive path')
                    if entry.filename in names:
                        parser.error('Duplicate archive path')
                    names.add(entry.filename)
                    combined.writestr(entry.filename, archive.read(entry))

    destination = f'/home/shinku/catgal-app/{args.version}'
    # mkdir refuses an existing release. Failed uploads stay in their new release
    # directory and can be investigated without overwriting a running version.
    extract_code = (
        'import io,sys,zipfile; '
        f'zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())).extractall({destination!r})'
    )
    command = (
        'umask 077 && mkdir -p /home/shinku/catgal-app && '
        f'mkdir {shlex.quote(destination)} && python3 -c {shlex.quote(extract_code)}'
    )
    result = subprocess.run(
        [ssh, '-i', str(key), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
         '-o', 'StrictHostKeyChecking=yes', '-o', f'UserKnownHostsFile={known_hosts}',
         'shinku@192.168.150.104', command],
        input=payload.getvalue(),
        check=False,
    )
    if result.returncode:
        sys.exit(result.returncode)
    print(f'Uploaded privately to {destination}')
    print(f'Run in the root Xshell: cd {destination}/deploy/linux && bash manage.sh deploy')


if __name__ == '__main__':
    main()
