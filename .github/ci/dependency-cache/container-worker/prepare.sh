#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends docker.io socat python3-venv ca-certificates curl jq git openjdk-21-jdk-headless
python3 -m venv /opt/container-worker
/opt/container-worker/bin/pip install --disable-pip-version-check 'testcontainers==4.15.0'
# Incoming candidates start in a different boot with only the runner-local dependency transport available.
# Keep the native daemon entirely inside the VM. Enable HTTP solely for that virtual endpoint.
install -d -m 0755 /etc/docker
printf '%s\n' '{"insecure-registries":["10.0.2.100:7777"]}' >/etc/docker/daemon.json
systemctl enable docker.service
systemctl restart docker.service
usermod -aG docker runner
cat >/etc/systemd/system/dependency-cache-relay.service <<'UNIT'
[Unit]
Description=Guest loopback to the job dependency cache
After=network.target
[Service]
User=nobody
NoNewPrivileges=true
ExecStart=/usr/bin/socat TCP-LISTEN:7777,bind=127.0.0.1,reuseaddr,fork TCP:10.0.2.100:7777
Restart=on-failure
[Install]
WantedBy=multi-user.target
UNIT
systemctl enable dependency-cache-relay.service
# Production jobs provide one fresh key from a read-only per-VM seed. Remove the preparation key and first-boot state.
rm -f /home/runner/.ssh/authorized_keys /etc/ssh/ssh_host_*
# Atomic cloud-init reseeding clears authorized keys; allow cloud-init to regenerate the SSH host keys.
systemctl enable ssh.service
cloud-init clean --logs --machine-id
sync
# Schedule shutdown before this authenticated channel closes; the preparation key has just been removed.
systemd-run --quiet --on-active=3s /usr/sbin/poweroff
