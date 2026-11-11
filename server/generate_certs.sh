#!/usr/bin/env bash
# Generate the lab certificates under certs/:
# - certs/mitm: mitmproxy CA (private key + CA cert), used to sign
# `secure.lab.test` and to intercept TLS in later tasks.
# - certs/secure.lab.test.{key,csr,pem}: leaf key/CSR/certificate signed by the
# mitmproxy CA. The leaf must be trusted by the Android app.

set -euo pipefail
cd "$(dirname "$0")"

CERTS=certs
MITM="$CERTS/mitm"
CA_CERT="$MITM/mitmproxy-ca-cert.pem"
CA_KEY="$MITM/mitmproxy-ca.pem"
LEAF_KEY="$CERTS/secure.lab.test.key"
LEAF_CSR="$CERTS/secure.lab.test.csr"
LEAF_PEM="$CERTS/secure.lab.test.pem"
mkdir -p "$CERTS"

# 1. Run mitmproxy once so it generates its CA in confdir.
if [[ ! -f "$CA_CERT" || ! -f "$CA_KEY" ]]; then
    absolute_confdir="$(cd "$CERTS" && pwd)/mitm"
    echo "[*] Generating mitmproxy CA in $MITM (brief mitmdump run)..."
    mitmdump --set "confdir=$absolute_confdir" \
             --listen-port 18080 --listen-host 127.0.0.1 >/dev/null 2>&1 &
    dump_pid=$!

    for _ in $(seq 1 50); do
        [[ -f "$MITM/mitmproxy-ca-cert.pem" && -f "$MITM/mitmproxy-ca.pem" ]] && break
        if ! kill -0 "$dump_pid" 2>/dev/null; then break; fi
        sleep 0.1
    done
    kill "$dump_pid" 2>/dev/null || true
    wait "$dump_pid" 2>/dev/null || true

    if [[ ! -f "$CA_CERT" && -f "$CERTS/mitmproxy-ca-cert.pem" ]]; then
        mkdir -p "$MITM"
        mv "$CERTS"/mitmproxy-ca-* "$MITM"/
    fi
fi

if [[ ! -f "$CA_CERT" || ! -f "$CA_KEY" ]]; then
    echo "ERROR: mitmproxy CA not found at $CA_CERT / $CA_KEY" >&2
    echo "Run: mitmdump --set confdir=$(pwd)/mitm" >&2
    exit 1
fi

# 2. Leaf CSR + key for secure.lab.test
# Regenerate the leaf if the key is missing, or if the existing cert does not
# verify against the CURRENT CA or mismatches the key (fresh clones, deleted
# keys, or a re-created CA would otherwise leave a stale/pair-broken cert).
leaf_valid() {
    [[ -f "$LEAF_KEY" && -f "$LEAF_PEM" ]] || return 1
    local cert_mod key_mod
    cert_mod=$(openssl x509 -in "$LEAF_PEM" -noout -modulus 2>/dev/null) || return 1
    key_mod=$(openssl rsa -in "$LEAF_KEY" -noout -modulus 2>/dev/null) || return 1
    [[ "$cert_mod" == "$key_mod" ]] || return 1
    openssl verify -CAfile "$CA_CERT" "$LEAF_PEM" >/dev/null 2>&1
}

if ! leaf_valid; then
    rm -f "$LEAF_PEM" "$LEAF_CSR"
    echo "[*] Generating leaf key + CSR for secure.lab.test..."
    openssl req -new -newkey rsa:2048 -nodes \
        -keyout "$LEAF_KEY" -out "$LEAF_CSR" \
        -subj "/CN=secure.lab.test/O=VPNLab"
fi

if [[ ! -f "$LEAF_PEM" ]]; then
    echo "[*] Signing leaf certificate with the mitmproxy CA..."
    cat > "$CERTS/leaf-ext.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:secure.lab.test
EOF
    openssl x509 -req -in "$LEAF_CSR" \
        -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
        -out "$LEAF_PEM" -days 3650 -sha256 -extfile "$CERTS/leaf-ext.cnf"
fi

echo "[*] Done. Files in $CERTS:"
ls -l "$CERTS" "$MITM"
