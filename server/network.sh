#!/usr/bin/env bash
# Bring the network up or down
# Usage: # ./network.sh <up|down|cleanup> [wifi-interface]
# Addresses: 10.42.0.1 gateway, 10.42.0.2 DNS server
#
# This script assumes the machine running it uses NetworkManager.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WIFI_IFACE="${2:-wlan0}"
STATE_DIR=/tmp/vpn-lab

# lab.test names -> addresses (used to add/remove aliases for the partner host)
PARTNER_IP="10.42.0.99"

# Replace wlan0 with wifi-interface
prepare_dnsmasq_conf() {
    mkdir -p "${STATE_DIR}"
    sed "s/^interface=.*/interface=${WIFI_IFACE}/" "${DIR}/dnsmasq.conf" \
        > "${STATE_DIR}/dnsmasq.conf.runtime"
}

# Start dnsmasq on the lab interface.
launch_dnsmasq() {
    local extra_conf="${1:-}"
    local err_file="${2:-${STATE_DIR}/dnsmasq.err}"
    local args=(-C "${STATE_DIR}/dnsmasq.conf.runtime")
    [[ -n "$extra_conf" ]] && args+=(--conf-file="$extra_conf")
    dnsmasq --test "${args[@]}" > /dev/null || {
        echo "ERROR: dnsmasq config syntax check failed (see dnsmasq.conf${extra_conf:+ / $extra_conf})" >&2
        return 1
    }
    dnsmasq "${args[@]}" \
        --interface="${WIFI_IFACE}" \
        --listen-address=10.42.0.2 \
        --pid-file="${STATE_DIR}/vpn-lab-dnsmasq.pid" 2> "${err_file}"
}

START_SERVICES() {
    mkdir -p "${STATE_DIR}"
    chmod 0777 "${STATE_DIR}" 2>/dev/null || true

    echo "[*] Starting hostapd (SSID VPN-LAB) on ${WIFI_IFACE}"
    pkill -f "hostapd.*hostapd.conf" 2>/dev/null || true
    sleep 1
    # hostapd -B runs as a daemon
    hostapd -B -i "${WIFI_IFACE}" "${DIR}/hostapd.conf" 2> "${STATE_DIR}/hostapd.err"
    sleep 2
    if ! pgrep -f "hostapd.*hostapd.conf" > /dev/null; then
        echo "ERROR: hostapd died immediately; check ${STATE_DIR}/hostapd.err" >&2
        exit 1
    fi
    echo "    hostapd running (pid $(pgrep -f 'hostapd.*hostapd.conf' | tr '\n' ' '))"

    echo "[*] Starting dnsmasq (DNS 10.42.0.2) on ${WIFI_IFACE}"
    pkill -f "dnsmasq.*dnsmasq.conf" 2>/dev/null || true
    sleep 1

    prepare_dnsmasq_conf
    launch_dnsmasq
    sleep 1
    if ! pgrep -f "dnsmasq.*dnsmasq.conf" > /dev/null; then
        echo "ERROR: dnsmasq died immediately; check ${STATE_DIR}/dnsmasq.err" >&2
        exit 1
    fi
    echo "    dnsmasq running (pid $(cat ${STATE_DIR}/vpn-lab-dnsmasq.pid))"

    echo "[+] Lab network up; DNS server at 10.42.0.2, gateway at 10.42.0.1"
}

stop_dnsmasq() {
    if [[ -f "${STATE_DIR}/vpn-lab-dnsmasq.pid" ]]; then
        kill "$(cat "${STATE_DIR}/vpn-lab-dnsmasq.pid")" 2>/dev/null || killall dnsmasq 2>/dev/null || true
        rm -f "${STATE_DIR}/vpn-lab-dnsmasq.pid"
    else
        killall dnsmasq 2>/dev/null || true
    fi
}

stop_hostapd() {
    pkill -f "hostapd.*hostapd.conf" 2>/dev/null || killall hostapd 2>/dev/null || true
    sleep 1
}

# Add an IPv4 alias to the Wi-Fi interface.
add_addr() {
    local ip="$1" label="$2"
    if [[ -n "$label" ]] && (( ${#WIFI_IFACE} + ${#label} <= 15 )); then
        ip addr add "$ip" dev "${WIFI_IFACE}" label "${WIFI_IFACE}${label}"
    else
        ip addr add "$ip" dev "${WIFI_IFACE}"
    fi
}

cmd_up() {
    echo "[*] Taking ${WIFI_IFACE} away from NetworkManager (if present)"
    if command -v nmcli > /dev/null 2>&1; then
        # Release any active connection on the interface
        nmcli device disconnect "${WIFI_IFACE}" 2>/dev/null || true
        nmcli device set "${WIFI_IFACE}" managed no 2>/dev/null || true
        sleep 1
    fi

    echo "[*] Configuring ${WIFI_IFACE} for the VPN lab"
    ip link set "${WIFI_IFACE}" down
    sleep 1

    # Addresses
    ip addr flush dev "${WIFI_IFACE}" scope global
    add_addr "10.42.0.1/24" ""
    add_addr "10.42.0.2/24" ":dns"
    add_addr "${PARTNER_IP}/24" ":partner"

    ip link set "${WIFI_IFACE}" up
    sleep 2

    echo "[*] Enabling IPv4 forwarding"
    sysctl -w net.ipv4.ip_forward=1 > /dev/null

    echo "[*] Setting up NAT on the AP interface"
    iptables -t nat -C POSTROUTING -o "${WIFI_IFACE}" -j MASQUERADE 2>/dev/null || \
        iptables -t nat -A POSTROUTING -o "${WIFI_IFACE}" -j MASQUERADE
    iptables -C FORWARD -i "${WIFI_IFACE}" -o "${WIFI_IFACE}" -j ACCEPT 2>/dev/null || \
        iptables -A FORWARD -i "${WIFI_IFACE}" -o "${WIFI_IFACE}" -j ACCEPT

    verify_lab_addresses
    START_SERVICES
}

verify_lab_addresses() {
    local ip_addr missing=0
    for ip_addr in 10.42.0.1 10.42.0.2 "${PARTNER_IP}"; do
        if ! ip -o addr show dev "${WIFI_IFACE}" 2>/dev/null | grep -q "inet ${ip_addr}/"; then
            echo "ERROR: ${ip_addr} is missing on ${WIFI_IFACE}" >&2
            missing=1
        fi
    done
    if (( missing )); then
        echo "       NetworkManager may have re-claimed the interface and flushed" >&2
        echo "       the lab addresses. Check: nmcli device show ${WIFI_IFACE}" >&2
        exit 1
    fi
}

teardown_network() {
    local iface="${1:-${WIFI_IFACE}}"

    echo "[*] Removing NAT and forwarding rules"
    iptables -t nat -D POSTROUTING -o "${iface}" -j MASQUERADE 2>/dev/null || true
    iptables -D FORWARD -i "${iface}" -o "${iface}" -j ACCEPT 2>/dev/null || true

    echo "[*] Removing addresses"
    ip addr del "${PARTNER_IP}/24" dev "${iface}" 2>/dev/null || true
    ip addr del 10.42.0.2/24 dev "${iface}" label "${iface}:dns" 2>/dev/null || true
    ip addr del 10.42.0.2/24 dev "${iface}" 2>/dev/null || true
    ip addr del 10.42.0.1/24 dev "${iface}" 2>/dev/null || true
    ip -6 addr del fd42:42::1/64 dev "${iface}" 2>/dev/null || true

    echo "[*] Disabling IPv4 forwarding"
    sysctl -w net.ipv4.ip_forward=0 > /dev/null 2>&1 || true
    echo "[*] Disabling IPv6 forwarding"
    sysctl -w net.ipv6.conf.all.forwarding=0 > /dev/null 2>&1 || true
}

# Verify the ports used by the lab are free (empty output = free).
ports_free() {
    local port bad=0
    for port in 1080 8080 8443; do
        if ss -tln 2>/dev/null | awk '{print $4}' | grep -E "[:.]${port}$" > /dev/null; then
            echo "WARNING: port ${port} is still in use:" >&2
            ss -tlnp "sport = :${port}" 2>/dev/null >&2 || true
            bad=1
        fi
    done
    return "${bad}"
}

cmd_down() {
    echo "[*] Stopping dnsmasq"
    stop_dnsmasq

    echo "[*] Stopping hostapd"
    stop_hostapd
    if command -v nmcli > /dev/null 2>&1; then
        nmcli device set "${WIFI_IFACE}" managed yes 2>/dev/null || true
    fi

    teardown_network "${WIFI_IFACE}"
    echo "[+] Lab network down"
}

# Full session teardown: lab services + network
cmd_cleanup() {
    echo "[*] Stopping lab server (run_server.sh stop)"
    "${DIR}/run_server.sh" stop || true

    echo "[*] Stopping mitmproxy (run_proxy.sh stop)"
    "${DIR}/run_proxy.sh" stop || true

    echo "[*] Stopping dnsmasq"
    stop_dnsmasq

    echo "[*] Stopping hostapd"
    stop_hostapd
    if command -v nmcli > /dev/null 2>&1; then
        nmcli device set "${WIFI_IFACE}" managed yes 2>/dev/null || true
    fi

    teardown_network "${WIFI_IFACE}"

    echo "[*] Checking lab ports (1080/8080/8443) are free"
    if ports_free; then
        echo "[+] Cleanup complete"
    else
        echo "[!] Cleanup finished but some ports are still in use (see warnings above)" >&2
        exit 1
    fi
}

cmd_ipv6_up() {
    echo "[*] OPTIONAL IPv6 helper"
    echo "[*] Assigning ULA fd42:42::1/64 to ${WIFI_IFACE} and sysctl-ing IPv6 on"
    sysctl -w net.ipv6.conf.all.forwarding=1 > /dev/null
    ip -6 addr add fd42:42::1/64 dev "${WIFI_IFACE}" 2>/dev/null || true

    echo "[*] Restarting dnsmasq with the optional RA config"
    stop_dnsmasq
    prepare_dnsmasq_conf
    launch_dnsmasq "${DIR}/dnsmasq-ipv6.conf"
    sleep 1
    if ! pgrep -f "dnsmasq.*dnsmasq.conf" > /dev/null; then
        echo "ERROR: dnsmasq with RA config died; check ${STATE_DIR}/dnsmasq.err; falling back" >&2
        stop_dnsmasq
        launch_dnsmasq "" "${STATE_DIR}/dnsmasq-ipv6-fallback.err"
    fi
    echo "[+] IPv6 helper active: RA announces fd42:42::/64 on ${WIFI_IFACE}"
}

cmd_ipv6_down() {
    echo "[*] Reverting optional IPv6 helper"
    stop_dnsmasq
    prepare_dnsmasq_conf
    launch_dnsmasq
    ip -6 addr del fd42:42::1/64 dev "${WIFI_IFACE}" 2>/dev/null || true
    sysctl -w net.ipv6.conf.all.forwarding=0 > /dev/null
    echo "[+] IPv6 helper off; dnsmasq back to IPv4-only config"
}

case "${1:-}" in
    up)        cmd_up ;;
    down)      cmd_down ;;
    ipv6-up)   cmd_ipv6_up ;;
    ipv6-down) cmd_ipv6_down ;;
    cleanup)   cmd_cleanup ;;
    *)
        echo "Usage: $0 {up|down|ipv6-up|ipv6-down|cleanup} [wifi-iface]   (default iface: wlan0)" >&2
        exit 1
        ;;
esac
