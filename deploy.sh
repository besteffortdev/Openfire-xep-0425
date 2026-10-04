#!/usr/bin/env bash
# deploy.sh — build and push the Message Moderation plugin to the test server.
#
# Targets ONE host: 2501-xmpp (192.168.101.10), the test server, as the sister SAMessage project's
# script does. Taken from that script unchanged apart from the plugin name and start marker, so a
# fix to one applies to the other.
#
# Two things this does that a naive script does not:
#
#   1. It never removes the old JAR first. The old rm -> wait -> copy dance regularly fell BETWEEN
#      PluginMonitor scans, which extracts the new JAR but never unloads the plugin — so the old
#      code keeps running while plugins/<name>/plugin.xml on disk shows the new version.
#      Checking that file is therefore NOT evidence of anything. Instead the JAR is copied to a
#      dot-prefixed temp name (invisible to the monitor's *.jar scan) and atomically mv'd over the
#      old one: jar-newer-than-extracted-dir is the reload path that works, and the monitor can
#      never read a half-written archive.
#
#   2. It VERIFIES the reload by looking for a fresh plugin-start line in the container log, and
#      force-touches the JAR once if the monitor missed it anyway.
#
# NOTE: this plugin ships ENABLED (plugin.messagemoderation.enabled = true). It needs no
# configuration: who may moderate is decided by each room's moderator role.
#
# Usage:
#   chmod +x deploy.sh
#   echo 'mypassword' > .ssh_pass && chmod 600 .ssh_pass
#   ./deploy.sh                # build + deploy
#   ./deploy.sh --no-build     # redeploy the JAR already in target/

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────────
SSH_TARGET="ubuntu-template@192.168.101.10"
CONTAINER="xmpp"

# File containing the SSH password (chmod 600). Leave empty to use keys / interactive auth.
SSH_PASS_FILE="${SSH_PASS_FILE:-.ssh_pass}"

OPENFIRE_PLUGIN_DIR="/var/lib/openfire/plugins"   # path INSIDE the container
PLUGIN_NAME="messagemoderation"
JAR="target/${PLUGIN_NAME}.jar"

# The line ModerationPlugin logs on every successful start. A start stamped after the JAR swap is
# the only reliable proof the new code is actually running.
START_MARKER="Message Moderation started"

# Seconds to wait per attempt for that line. A clean unload+extract+load takes ~10-25s.
VERIFY_WAIT=45
OPENFIRE_LOG="/var/log/openfire/openfire.log"      # lives INSIDE the container, not on the host

# Bytes per piece when shipping the JAR. See ship_jar() for why this is not one scp.
CHUNK_BYTES=32768
# ───────────────────────────────────────────────────────────────────────────────

BUILD=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) BUILD=0; shift ;;
        *)          echo "Unknown option: $1"; exit 2 ;;
    esac
done

SSH_PREFIX=""
if [[ -n "$SSH_PASS_FILE" && -f "$SSH_PASS_FILE" ]]; then
    if ! command -v sshpass &>/dev/null; then
        echo "ERROR: SSH_PASS_FILE is set but 'sshpass' is not installed."
        echo "       Install it with:  sudo apt install -y sshpass"
        exit 1
    fi
    SSH_PREFIX="sshpass -f $(printf '%q' "$SSH_PASS_FILE")"
    echo "==> Using password file: $SSH_PASS_FILE"
    SUDO_PASS_SRC="$SSH_PASS_FILE"
else
    echo "==> No password file found — using SSH keys / interactive auth"
    SUDO_PASS_SRC=/dev/null
fi

if [[ $BUILD -eq 1 ]]; then
    echo "==> Building plugin..."
    mvn package -q
fi

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: Plugin archive not found at ${JAR}. Run mvn package first."
    exit 1
fi
echo "==> Using JAR: $JAR ($(du -h "$JAR" | cut -f1), version $(unzip -p "$JAR" plugin.xml \
    | sed -n 's#.*<version>\(.*\)</version>.*#\1#p'))"

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=10)

# Ships the JAR to /tmp on the server, in pieces, and verifies it by checksum.
#
# This is NOT premature caution. A single scp of the ~120 KB JAR to 192.168.101.10 hangs partway
# and never finishes, while the same file to 192.168.102.10/103.10 succeeds and a DOWNLOAD from
# 192.168.101.10 succeeds. The path is asymmetric: this controller (192.168.40.31) reaches
# 192.168.101.10 through the router at 192.168.40.17, but that host also has ens35 on
# 192.168.40.33 — the controller's own subnet — so its replies go straight back and never traverse
# the router. Something in that one-sided path (a stateful firewall seeing only half a flow is the
# best fit) kills sustained transfers; rate-limiting with `scp -l` does not help, and the transfer
# dies at a different offset each time. Small transfers finish before it bites, so chunking works
# where one large copy does not.
#
# The real fix belongs in the network, not here. If the routing is ever made symmetric, delete this
# and use a plain scp — the checksum check below will tell you honestly whether it worked.
ship_jar() {
    local staging="$1"
    local want
    want=$(md5sum "$JAR" | cut -d' ' -f1)

    local tmpdir
    tmpdir=$(mktemp -d)
    # shellcheck disable=SC2064  # expand tmpdir now, on purpose
    trap "rm -rf '$tmpdir'" RETURN
    split -b "$CHUNK_BYTES" "$JAR" "$tmpdir/part."

    local total
    total=$(find "$tmpdir" -name 'part.*' | wc -l)
    echo "    Shipping in ${total} piece(s) (see ship_jar() for why this is not one scp)…"

    $SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "rm -rf '$staging' && mkdir -p '$staging'"

    local part n=0
    for part in "$tmpdir"/part.*; do
        n=$((n+1))
        local attempt=0
        until $SSH_PREFIX scp -q "${SSH_OPTS[@]}" "$part" "${SSH_TARGET}:${staging}/"; do
            attempt=$((attempt+1))
            if [[ $attempt -ge 3 ]]; then
                echo "    ERROR: piece ${n}/${total} failed after 3 attempts."
                return 1
            fi
            echo "    piece ${n}/${total} failed, retrying (${attempt}/3)…"
        done
    done

    # Reassemble and prove it byte for byte. A truncated JAR would otherwise be swapped in and
    # fail at class-load time, long after this script had reported success.
    local got
    got=$($SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
        cat '${staging}'/part.* > '${staging}/${PLUGIN_NAME}.jar' &&
        md5sum '${staging}/${PLUGIN_NAME}.jar' | cut -d' ' -f1")

    if [[ "$got" != "$want" ]]; then
        echo "    ERROR: checksum mismatch after reassembly (want ${want}, got ${got})."
        return 1
    fi
    echo "    Transfer verified (md5 ${want})."
}

# Polls inside the container (so every timestamp shares its clock) until the plugin logs a start
# line newer than $1, the pre-deploy container time. If the first wait expires, touches the JAR —
# jar newer than the extracted dir makes PluginMonitor do a proper unload+reload — and waits again.
verify_reload() {
    local deploy_ts="$1"
    local remote="
        started_after() {
            last=\$(grep -a '${START_MARKER}' '${OPENFIRE_LOG}' 2>/dev/null | tail -1 \
                    | cut -d' ' -f1-2 | tr -dc '0-9' | cut -c1-14)
            [ -n \"\$last\" ] && [ \"\$last\" -gt '${deploy_ts}' ]
        }
        i=0
        while [ \$i -lt ${VERIFY_WAIT} ]; do
            started_after && { echo '    Plugin reloaded (verified).'; exit 0; }
            sleep 1; i=\$((i+1))
        done
        echo '    Hot-reload missed — touching the JAR to force it…'
        touch '${OPENFIRE_PLUGIN_DIR}/${PLUGIN_NAME}.jar'
        i=0
        while [ \$i -lt ${VERIFY_WAIT} ]; do
            started_after && { echo '    Plugin reloaded after touch (verified).'; exit 0; }
            sleep 1; i=\$((i+1))
        done
        echo '    ERROR: plugin did NOT reload — check ${OPENFIRE_LOG} in the container.'
        exit 1
    "
    $SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
        "docker exec '$CONTAINER' sh -c $(printf '%q' "$remote")"
}

echo ""
echo "──> Deploying to ${SSH_TARGET} (container: ${CONTAINER})"

# Docker group membership is lost when a VM is rebuilt from the template; usermod takes effect
# at the next SSH login, i.e. in time for the docker commands below.
$SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
    set -e
    if ! id -nG | grep -qw docker; then
        sudo -S -p '' usermod -aG docker \"\$(whoami)\"
        echo '    Added user to docker group.'
    fi
" < "$SUDO_PASS_SRC"

STAGING="/tmp/${PLUGIN_NAME}-deploy"
ship_jar "$STAGING"

# Note the container clock BEFORE the jar lands — any start stamped after this is the new jar.
DEPLOY_TS=$($SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
    "docker exec '$CONTAINER' date +%Y%m%d%H%M%S")

$SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
    set -e
    docker cp '${STAGING}/${PLUGIN_NAME}.jar' \
              '${CONTAINER}:${OPENFIRE_PLUGIN_DIR}/.${PLUGIN_NAME}.jar.tmp'
    docker exec '$CONTAINER' mv -f \
        '${OPENFIRE_PLUGIN_DIR}/.${PLUGIN_NAME}.jar.tmp' \
        '${OPENFIRE_PLUGIN_DIR}/${PLUGIN_NAME}.jar'
    rm -rf '${STAGING}'
    echo '    New JAR swapped in — waiting for Openfire to reload the plugin…'
"

if ! verify_reload "$DEPLOY_TS"; then
    echo ""
    echo "==> WARNING: plugin reload NOT confirmed on ${SSH_TARGET}."
    echo "    Check ${OPENFIRE_LOG} inside the ${CONTAINER} container."
    exit 1
fi

echo ""
echo "==> Done. ${SSH_TARGET} updated (reload verified)."
echo "    Running version:"
$SSH_PREFIX ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
    "docker exec '$CONTAINER' grep -o '<version>[^<]*</version>' \
        '${OPENFIRE_PLUGIN_DIR}/${PLUGIN_NAME}/plugin.xml' | sed 's/^/      /'"
