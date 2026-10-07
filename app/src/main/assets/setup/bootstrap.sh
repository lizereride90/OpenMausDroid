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

log "[1/7] updating the package index"
apt-get update -y

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
