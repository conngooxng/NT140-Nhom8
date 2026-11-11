#!/usr/bin/env python3
"""
    An addon for mitmproxy that manipulates traffic:
    - Rewrite headers/bodies
    - Faux-adblocking by blocking requests to ads.lab.test and tracker.lab.test
    - Inject /injection.js to HTTP responses
    - Change all 3XX locations to the "partner" page
"""

from __future__ import annotations

import mitmproxy.http

BLOCKED_HOSTS = {"ads.lab.test", "tracker.lab.test"}
REWRITE_MARKER = "VPNLAB-REWRITTEN"
INJECT_MARKER = "VPNLAB-INJECTED"
PARTNER_TARGET = "http://partner.lab.test:8080/partner"

BLOCKED_PAGE = (
    "<html><body>" + REWRITE_MARKER + " ad/tracker blocked</body></html>"
)


def _blocked_hosts(flow: mitmproxy.http.HTTPFlow) -> bool:
    """True if the flow targets an ad/tracker host (SOCKS address or Host header)."""
    host_header = flow.request.headers.get("Host", "").split(":", 1)[0].lower()
    hosts = {flow.request.host, host_header}
    return bool(hosts & BLOCKED_HOSTS)


def _blocked_response() -> mitmproxy.http.Response:
    return mitmproxy.http.Response.make(
        403,
        BLOCKED_PAGE,
        {"Content-Type": "text/html; charset=utf-8"},
    )


def _authority_host(location: str) -> str:
    """Host part of an absolute URL (empty for relative locations)."""
    if "://" not in location:
        return ""
    rest = location.split("://", 1)[1]
    return rest.split("/", 1)[0].split(":", 1)[0]


def request(flow: mitmproxy.http.HTTPFlow) -> None:
    """Inject the interception marker and enforce the ad/tracker blocklist."""
    flow.request.headers["X-VPNLab"] = "rewritten"

    if _blocked_hosts(flow):
        flow.response = _blocked_response()


def response(flow: mitmproxy.http.HTTPFlow) -> None:
    """Rewrite bodies, inject JavaScript, and hijack redirects."""
    flow.response.headers["X-VPNLab"] = "rewritten"
    location = flow.response.headers.get("Location")
    if 300 <= flow.response.status_code < 400 and location:
        if _authority_host(location).endswith(".lab.test"):
            flow.response.headers["Location"] = PARTNER_TARGET

    content_type = flow.response.headers.get("Content-Type", "")
    if flow.response.status_code != 200 or "text/html" not in content_type:
        return
    try:
        text = flow.response.get_text()
    except ValueError:
        return  # undecodable body: leave it untouched

    if "VPNLAB-ADS" in text:
        text = BLOCKED_PAGE

    script_src = (f"//{flow.request.host}:{flow.request.port}/injected.js")
    injection = (
        "<script src=\"" + script_src + "\"></script>"
        "<script>/* " + INJECT_MARKER + " */</script>"
    )

    if "</body>" in text:
        text = text.replace("</body>", injection + "</body>", 1)
    else:
        # Fallback for pages without a closing <body>: append so the
        # injection never silently no-ops.
        text += injection
    flow.response.set_text(text)
