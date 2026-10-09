#!/bin/bash
# OpenMausDroid first-run bootstrap. Runs INSIDE the Ubuntu rootfs under proot,
# as the app's own uid (proot -0 fakes root). Needs network access.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export npm_config_unsafe_perm=

log() { echo "[bootstrap] $*"; }

if [ -f /etc/maus-bootstrap-done ]; then
  log "already bootstrapped, skipping"
  exit 0
fi

mkdir -p /run/maus /var/log/maus /root/.vnc /root/.openmausbot /root/.cache
chmod 700 /run/maus

log "[1/7] configuring DNS and updating the package index"
# Ubuntu base images ship an empty resolv.conf. Android's resolver is not
# available to guest glibc, so provide public resolvers before contacting APT.
cat > /etc/resolv.conf <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
options timeout:2 attempts:3
EOF
# Base image has no CA store; install the bundled one so HTTPS works.
install -Dm644 /setup/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# Point APT at ports over HTTPS and mark the repo trusted. Signature
# verification via gpgv/apt-key is unreliable under proot (the faked root
# user breaks apt-key's readability checks), so we rely on TLS instead.
rm -f /etc/apt/sources.list
cat > /etc/apt/sources.list.d/ubuntu.sources <<'EOF'
Types: deb
URIs: https://ports.ubuntu.com/ubuntu-ports
Suites: noble noble-updates noble-backports noble-security
Components: main restricted universe multiverse
Trusted: yes
EOF
# Many Android networks are IPv4-only and ports.ubuntu.com advertises IPv6
# first, so force IPv4 and retry to avoid multi-minute stalls.
cat > /etc/apt/apt.conf.d/99openmaus <<'EOF'
Acquire::ForceIPv4 "true";
Acquire::Retries "3";
Acquire::https::Timeout "20";
EOF
rm -rf /var/lib/apt/lists/*
apt-get update -y
if ! compgen -G "/var/lib/apt/lists/*_Packages*" > /dev/null; then
  log "apt update completed without any package indexes"
  exit 1
fi

log "[2/7] installing desktop, VNC and base tools"
apt-get install -y --no-install-recommends \
  ca-certificates curl git unzip xz-utils procps iproute2 net-tools \
  dbus-x11 fonts-dejavu-core xfonts-base \
  xfce4 xfce4-terminal \
  tigervnc-standalone-server tigervnc-common \
  websockify python3

log "[2b/7] installing icon themes (optional)"
apt-get install -y --no-install-recommends hicolor-icon-theme gnome-icon-theme \
  || log "icon themes skipped"

log "[3/7] installing Node.js 24"
NODE_VERSION=v24.21.0
curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.xz" -o /tmp/node.tar.xz
tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
rm -f /tmp/node.tar.xz
log "node $(node -v), npm $(npm -v)"

log "[4/7] installing OpenMausBot"
npm install -g openmausbot --no-audit --no-fund --loglevel=error
command -v openmausbot >/dev/null || { log "openmausbot missing after install"; exit 1; }

log "[5/7] installing agent CLIs (optional)"
npm install -g @anthropic-ai/claude-code --no-audit --no-fund --loglevel=error \
  || log "claude-code install failed (install later from Settings)"
npm install -g @openai/codex --no-audit --no-fund --loglevel=error \
  || log "codex install failed (install later from Settings)"

log "[6/7] installing noVNC"
curl -fsSL https://codeload.github.com/novnc/noVNC/tar.gz/refs/tags/v1.7.0 -o /tmp/novnc.tgz
mkdir -p /opt/noVNC
tar -xzf /tmp/novnc.tgz -C /opt/noVNC --strip-components=1
rm -f /tmp/novnc.tgz
[ -f /opt/noVNC/vnc.html ] || { log "noVNC extraction failed"; exit 1; }

log "[7/7] finalizing"
if [ -f /setup/ttyd ]; then
  install -m 755 /setup/ttyd /usr/local/bin/ttyd
fi
printf 'openmaus\nopenmaus\n' | vncpasswd -f > /root/.vnc/passwd
chmod 600 /root/.vnc/passwd
install -m 755 /setup/maus-session /usr/local/bin/maus-session
install -m 755 /setup/maus-harness /usr/local/bin/maus-harness
install -m 755 /setup/maus-ttyd /usr/local/bin/maus-ttyd

apt-get clean
rm -rf /var/lib/apt/lists/* /root/.cache/*
touch /etc/maus-bootstrap-done
log "done"
