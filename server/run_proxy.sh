#!/usr/bin/env bash
# Usage: ./run_proxy.sh start|stop|status|run
# - start: background start
# - run: foreground start
#
# Listens on 10.42.0.1:1080 by default, unless specified by ${HOST}:${PORT}.
# If ${HOST} isn't available, listen on 127.0.0.1. 

set -euo pipefail
cd "$(dirname "$0")"

STATE_DIR="${LAB_STATE_DIR:-/tmp/vpn-lab}"
mkdir -p "$STATE_DIR"
PID_FILE="$STATE_DIR/vpn-lab-proxy.pid"
LOG_FILE="$STATE_DIR/vpn-lab-proxy.log"

CERTS="$(pwd)/certs"
CONFDIR="$CERTS/mitm"
UPSTREAM_CA="$CONFDIR/mitmproxy-ca-cert.pem"

HOST="${LAB_SOCKS_HOST:-10.42.0.1}"
PORT="${LAB_SOCKS_PORT:-1080}"
BIND="$HOST"

if ! ip -o addr show 2>/dev/null | grep -q "inet ${HOST}/"; then
    if [ "${1:-}" = "run" ] || [ "${1:-}" = "start" ]; then
        echo "[run_proxy.sh] warning: ${HOST} not on any interface; binding 127.0.0.1 instead" >&2
    fi
    BIND="127.0.0.1"
fi

case "${1:-status}" in
    start)
        if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "[run_proxy.sh] already running (pid $(cat "$PID_FILE"))"
            exit 0
        fi
        nohup mitmdump --mode socks5 --listen-host "$BIND" --listen-port "$PORT" \
            --set confdir="$CONFDIR" \
            --set "ssl_verify_upstream_trusted_ca=$UPSTREAM_CA" \
            -s ./mitm_addon.py >>"$LOG_FILE" 2>&1 &
        pid=$!
        echo "$pid" >"$PID_FILE"
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            echo "[run_proxy.sh] started mitmdump pid $pid (socks5 ${BIND}:${PORT}, log: $LOG_FILE)"
        else
            echo "[run_proxy.sh] FAILED to start; see $LOG_FILE" >&2
            tail -n 20 "$LOG_FILE" >&2
            exit 1
        fi
        ;;
    stop)
        if [[ -f "$PID_FILE" ]]; then
            pid=$(cat "$PID_FILE")
            if kill -0 "$pid" 2>/dev/null; then
                kill "$pid"
                echo "[run_proxy.sh] stopped pid $pid"
            fi
            rm -f "$PID_FILE"
        else
            echo "[run_proxy.sh] not running"
        fi
        ;;
    status)
        if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "[run_proxy.sh] running (pid $(cat "$PID_FILE"))"
        else
            echo "[run_proxy.sh] not running"
            exit 1
        fi
        ;;
    run)
        exec mitmdump --mode socks5 --listen-host "$BIND" --listen-port "$PORT" \
            --set confdir="$CONFDIR" \
            --set "ssl_verify_upstream_trusted_ca=$UPSTREAM_CA" \
            -s ./mitm_addon.py
        ;;
    *)
        echo "usage: $0 {start|stop|status|run}" >&2
        exit 2
        ;;
esac
