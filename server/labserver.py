#!/usr/bin/env python3
""" Mock HTTP(S) server. Returns these endpoints:

    /baseline:      VPNLAB-BASELINE
    /ads:           ad content (blocked/rewritten by the mitmproxy addon later)
    /redirect:      302 to /landing
    /landing:       VPNLAB-BASELINE (destination of the redirect)
    /secure:        VPNLAB-SECURE (HTTPS only in practice)
    /injected.js:   JavaScript containing VPNLAB-INJECTED
    /partner:       VPNLAB-PARTNER (also served for any path when the client
                    connects to the partner.lab.test vhost)

Run via scripts/run_server.sh (../../../server/run_server.sh).
"""

import os
import ssl
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CERT_DIR = os.path.join(BASE_DIR, "certs")
LEAF_CERT = os.path.join(CERT_DIR, "secure.lab.test.pem")
LEAF_KEY = os.path.join(CERT_DIR, "secure.lab.test.key")

HTTP_PORT = int(os.environ.get("LAB_HTTP_PORT", "8080"))
HTTPS_PORT = int(os.environ.get("LAB_HTTPS_PORT", "8443"))

PARTNER_HOSTS = {"partner.lab.test", "10.42.0.99"}

INDEX_HTML = """<html><body><h1>VPN-LAB index</h1>
<p>Endpoints: /baseline /ads /redirect /landing /secure /injected.js /partner</p>
</body></html>"""


class LabHandler(BaseHTTPRequestHandler):
    server_version = "VPNLabServer/1.0"

    # --- helpers -------------------------------------------------------

    def _host(self) -> str:
        return (self.headers.get("Host") or "").split(":")[0].lower()

    def _send(self, body: bytes, content_type: str = "text/html; charset=utf-8") -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_partner(self) -> None:
        self._send(b"<html><body>VPNLAB-PARTNER partner content</body></html>")

    def _route(self) -> None:
        path = self.path.split("?", 1)[0]

        # Partner vhost: anything requested from partner.lab.test carries
        # VPNLAB-PARTNER so interception evidence is unambiguous.
        if self._host() in PARTNER_HOSTS:
            self._send_partner()
            return

        if path == "/":
            self._send(INDEX_HTML.encode())
        elif path == "/baseline" or path == "/landing":
            self._send(b"<html><body>VPNLAB-BASELINE baseline page</body></html>")
        elif path == "/ads":
            self._send(
                b"<html><body>VPNLAB-ADS ad content served by the lab</body></html>",
                content_type="text/html; charset=utf-8",
            )
        elif path == "/redirect":
            target = self._absolute(location_path="/landing")
            self.send_response(HTTPStatus.FOUND)
            self.send_header("Location", target)
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif path == "/secure":
            self._send(b"<html><body>VPNLAB-SECURE secure page</body></html>")
        elif path == "/injected.js":
            js = b"// VPNLAB-INJECTED\nconsole.log('VPNLAB-INJECTED');\n"
            self._send(js, content_type="application/javascript")
        elif path == "/partner":
            self._send_partner()
        else:
            self.send_response(HTTPStatus.NOT_FOUND)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def _absolute(self, location_path: str) -> str:
        # Redirect back to the host the client used, so the redirect is a
        # cross-host navigation like a real tracking redirect.
        scheme = "https" if getattr(self.server, "is_https", False) else "http"
        host = self.headers.get("Host") or "http.lab.test"
        return f"{scheme}://{host}{location_path}"

    # --- verbs ---------------------------------------------------------

    def do_GET(self) -> None:
        self._route()

    def do_HEAD(self) -> None:
        self._route()

    def do_POST(self) -> None:
        # Accept and discard bodies; victims may POST.
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        self._route()

    def log_message(self, fmt: str, *args) -> None:
        print(f"[labserver] {self.address_string()} {fmt % args}", flush=True)


def make_http_server() -> ThreadingHTTPServer:
    return ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), LabHandler)


def make_https_server() -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer(("0.0.0.0", HTTPS_PORT), LabHandler)
    srv.is_https = True
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(LEAF_CERT, LEAF_KEY)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    return srv


def main() -> None:
    if not (os.path.exists(LEAF_CERT) and os.path.exists(LEAF_KEY)):
        raise SystemExit(
            f"Missing leaf certificate: {LEAF_CERT}. Run ./generate_certs.sh first."
        )
    httpd = make_http_server()
    httpsd = make_https_server()
    threading.Thread(target=httpsd.serve_forever, daemon=True).start()
    print(
        f"[labserver] HTTP on :{HTTP_PORT}, HTTPS on :{HTTPS_PORT} "
        f"(cert CN=secure.lab.test, signed by the mitmproxy CA)",
        flush=True,
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.shutdown()
        httpsd.shutdown()


if __name__ == "__main__":
    main()
