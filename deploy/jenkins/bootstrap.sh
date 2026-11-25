#!/usr/bin/env bash
set -euo pipefail
umask 022

if [[ $EUID -ne 0 ]]; then
    echo 'Run this file in the authorized root Xshell session.' >&2
    exit 1
fi
bundle=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
app=/home/shinku/catgal-app
worker=/opt/catgal-jenkins
spool=/var/lib/catgal-jenkins
jenkins_home=/var/lib/jenkins
apt_key=/etc/apt/keyrings/catgal-jenkins.asc
apt_source=/etc/apt/sources.list.d/catgal-jenkins.list
key_fingerprint=5E386EADB55F01504CAE8BCF7198F4B714ABFC68
local_package=${1:-}

# 复用已运行的应用和 Docker，安装前后核对同一个入口。
test -f "$app/.deploy/state.json"
docker compose version
curl -fsS --max-time 10 http://127.0.0.1:8088/health/live >/dev/null
if ! systemctl is-active --quiet jenkins.service && ss -Hln 'sport = :8089' | grep -q .; then
    echo 'Port 8089 is already in use.' >&2
    exit 1
fi

apt_options=(-o Acquire::Retries=2 -o Acquire::http::Timeout=25 -o Acquire::https::Timeout=25)
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
install -d -m 0755 /etc/apt/keyrings /etc/systemd/system/jenkins.service.d
key_tmp=$(mktemp)
trap 'rm -f -- "$key_tmp"' EXIT
curl --fail --silent --show-error --location --retry 2 --max-time 60 \
    https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key -o "$key_tmp"
gpg --batch --with-colons --show-keys "$key_tmp" | awk -F: -v expected="$key_fingerprint" \
    '$1 == "pub" { keys++ } $1 == "fpr" && !first { first = $10 } END { exit(keys != 1 || first != expected) }'
install -m 0644 "$key_tmp" "$apt_key"
printf 'deb [signed-by=%s] https://pkg.jenkins.io/debian-stable binary/\n' "$apt_key" > "$apt_source"

# 在 APT 第一次启动 Jenkins 前设置本地回环端口。
cat > /etc/systemd/system/jenkins.service.d/catgal.conf <<'SYSTEMD'
[Service]
Environment="JENKINS_PORT=8089"
Environment="JENKINS_OPTS=--httpListenAddress=127.0.0.1"
Environment="JAVA_OPTS=-Djava.awt.headless=true -Xmx768m"
SYSTEMD
systemctl daemon-reload
if [[ -n $local_package ]]; then
    # 弱网时可从工作站预取。同版本的本地包必须匹配 VM 已签名 APT 索引。
    local_package=$(realpath -e -- "$local_package")
    test "$(dpkg-deb -f "$local_package" Package)" = jenkins
    version=$(dpkg-deb -f "$local_package" Version)
    expected=$(apt-cache show "jenkins=$version" | awk '$1 == "SHA256:" && !seen++ { print $2 }')
    [[ $expected =~ ^[0-9a-f]{64}$ ]]
    printf '%s  %s\n' "$expected" "$local_package" | sha256sum --check --status
    apt-get "${apt_options[@]}" install -y --no-install-recommends fontconfig openjdk-21-jre-headless "$local_package"
elif ! dpkg-query -W -f='${db:Status-Status}\n' jenkins 2>/dev/null | grep -qx installed; then
    apt-get "${apt_options[@]}" update
    apt-get "${apt_options[@]}" install -y --no-install-recommends fontconfig openjdk-21-jre-headless
    apt-get "${apt_options[@]}" install -y --no-install-recommends jenkins
fi

install -d -o root -g root -m 0755 "$worker" "$spool" /etc/catgal-jenkins
install -d -o root -g jenkins -m 2730 "$spool/incoming"
install -d -o root -g jenkins -m 2750 "$spool/status"
install -d -o root -g root -m 0700 "$spool/processing"
install -m 0644 "$bundle/deploy/linux/"{deploy_release.py,release_common.py,storage_release.py,compose.yaml,smoke.sh} "$worker/"
install -m 0644 "$bundle/deploy/jenkins/"{release_queue.py,configure.groovy} "$worker/"
install -m 0644 "$bundle/deploy/jenkins/catgal-jenkins-deploy.service" /etc/systemd/system/

# 专用密钥暂存在 Jenkins 用户的私有目录，插件就绪后导入 Credentials。
install -d -o jenkins -g jenkins -m 0700 "$jenkins_home/.ssh" "$jenkins_home/init.groovy.d"
if [[ ! -f $jenkins_home/.ssh/catgal-github.pub ]]; then
    runuser -u jenkins -- ssh-keygen -q -t ed25519 -N '' -C 'catgal-jenkins-readonly' \
        -f "$jenkins_home/.ssh/catgal-github"
fi
github_host='github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl'
touch "$jenkins_home/.ssh/known_hosts"
if ! grep -qxF "$github_host" "$jenkins_home/.ssh/known_hosts"; then
    printf '%s\n' "$github_host" >> "$jenkins_home/.ssh/known_hosts"
fi
chown jenkins:jenkins "$jenkins_home/.ssh/known_hosts"
chmod 0600 "$jenkins_home/.ssh/known_hosts"
install -o root -g jenkins -m 0640 "$bundle/deploy/jenkins/setup.groovy" "$jenkins_home/init.groovy.d/catgal.groovy"

systemctl daemon-reload
systemctl enable --now catgal-jenkins-deploy.service
systemctl enable jenkins.service
systemctl restart jenkins.service
for attempt in {1..24}; do
    if curl -fsS --max-time 4 http://127.0.0.1:8089/login >/dev/null; then
        break
    fi
    sleep 5
done
curl -fsS --max-time 5 http://127.0.0.1:8089/login >/dev/null
if ss -Hln 'sport = :8089' | awk '{print $4}' | grep -vxF '127.0.0.1:8089' | grep -q .; then
    systemctl stop jenkins.service
    echo 'Jenkins bound outside loopback; stopped for inspection.' >&2
    exit 1
fi
curl -fsS --max-time 10 http://127.0.0.1:8088/health/live >/dev/null
systemctl is-active jenkins.service catgal-jenkins-deploy.service
dpkg-query -W -f='${Package} ${Version}\n' jenkins openjdk-21-jre-headless
echo 'Catgal Jenkins service ready. Deployment remains gated by /etc/catgal-jenkins/deploy-enabled.'
echo 'Add this read-only deploy key to shrikayuki/catgal:'
cat "$jenkins_home/.ssh/catgal-github.pub"
