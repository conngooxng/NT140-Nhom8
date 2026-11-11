#!/usr/bin/env bash
# Usage: ./run_server.sh start|stop|status|run
# - start: background start
# - run: foreground start

set -euo pipefail
cd "$(dirname "$0")"

STATE_DIR="${LAB_STATE_DIR:-/tmp/vpn-lab}"
mkdir -p "$STATE_DIR"
PID_FILE="$STATE_DIR/vpn-lab-server.pid"
LOG_FILE="$STATE_DIR/vpn-lab-server.log"

case "${1:-status}" in
    start)
        if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "[run_server.sh] already running (pid $(cat "$PID_FILE"))"
            exit 0
        fi
        nohup python3 ./labserver.py >>"$LOG_FILE" 2>&1 &
        pid=$!
        echo "$pid" >"$PID_FILE"
        sleep 0.5
        if kill -0 "$pid" 2>/dev/null; then
            echo "[run_server.sh] started labserver.py pid $pid (log: $LOG_FILE)"
        else
            echo "[run_server.sh] FAILED to start; see $LOG_FILE" >&2
            tail -n 20 "$LOG_FILE" >&2
            exit 1
        fi
        ;;
    stop)
        if [[ -f "$PID_FILE" ]]; then
            pid=$(cat "$PID_FILE")
            if kill -0 "$pid" 2>/dev/null; then
                kill "$pid"
                echo "[run_server.sh] stopped pid $pid"
            fi
            rm -f "$PID_FILE"
        else
            echo "[run_server.sh] not running"
        fi
        ;;
    status)
        if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "[run_server.sh] running (pid $(cat "$PID_FILE"))"
        else
            echo "[run_server.sh] not running"
            exit 1
        fi
        ;;
    run)
        python3 ./labserver.py
        ;;
    *)
        echo "usage: $0 {start|stop|status|run}" >&2
        exit 2
        ;;
esac
