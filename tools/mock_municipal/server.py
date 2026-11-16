#!/usr/bin/env python3
"""Mock of the municipal 106 API for tests and load tests. Standard library only.

Mimics the submission path the app uses (see MunicipalClient.kt): POST Login,
createupdatecontact, CreateCase, AddFileToCase and GET GetSubjectCategories under
/CRMCityConnect/api/jerusalem/. All data is synthetic. Nothing is persisted:
photos are counted and hashed, never stored. It never talks to the real city.

Behaviour that is NOT known from the real API is marked "assumed" below; the
mock only needs to be a stable stand-in, not a replica.

Scenarios (set at start with --scenario or at runtime with POST /__mock/scenario):
  ok            normal Success=true answers
  rate_limit    HTTP 429 with Retry-After: 1
  server_error  HTTP 500
  slow          answer after --delay seconds (default 3)
  bad_json      HTTP 200 with a body that is not JSON
  bad_success   HTTP 200 JSON with "Success": "true" (a string, not a boolean)
  failure       HTTP 200 JSON with "Success": false
  duplicate     (assumed) CreateCase with an identical body to an earlier one gets
                Success=false; a different body succeeds
  redirect      HTTP 302 (the client must refuse it)
  drop          close the connection with no answer
Optional "endpoint" limits a scenario to one endpoint; "count" applies it to the
next N matching requests, then falls back to ok.

Runtime control: POST /__mock/scenario {"mode":..., "endpoint":..., "count":..., "delay":...}
                 GET /__mock/stats (per-endpoint request counts, to assert "no retry")
                 POST /__mock/reset
"""
import argparse
import hashlib
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PREFIX = "/CRMCityConnect/api/jerusalem/"
ENDPOINTS = {"Login", "createupdatecontact", "CreateCase", "AddFileToCase", "GetSubjectCategories"}
CATEGORIES = [  # synthetic stand-in catalog
    {"SubjectId": 1, "SubjectName": "חניה", "SubSubjects": [{"SubjectId": 11, "SubjectName": "חניה על מדרכה"}]},
    {"SubjectId": 2, "SubjectName": "תחזוקה", "SubSubjects": [{"SubjectId": 21, "SubjectName": "מפגע בדיקה"}]},
]
LOCK = threading.Lock()
STATE = {}


def reset(initial="ok", delay=3.0):
    with LOCK:
        STATE.clear()
        STATE.update(mode=initial, endpoint=None, count=None, delay=delay,
                     stats={}, seen_cases={}, next_case=1, tokens=set())


def scenario_for(endpoint):
    with LOCK:
        mode, ep, count = STATE["mode"], STATE["endpoint"], STATE["count"]
        if mode == "ok" or (ep and ep != endpoint):
            return "ok", STATE["delay"]
        if count is not None:
            if count <= 0:
                STATE["mode"] = "ok"
                return "ok", STATE["delay"]
            STATE["count"] = count - 1
        return mode, STATE["delay"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # quiet
        pass

    def _send(self, code, payload=None, raw=None, headers=None):
        body = raw if raw is not None else json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _control(self, method, path):
        if path == "/__mock/stats" and method == "GET":
            with LOCK:
                return self._send(200, {"requests": dict(STATE["stats"]), "mode": STATE["mode"]})
        if path == "/__mock/reset" and method == "POST":
            reset(); return self._send(200, {"ok": True})
        if path == "/__mock/scenario" and method == "POST":
            try:
                req = json.loads(self._body() or b"{}")
            except ValueError:
                return self._send(400, {"error": "bad json"})
            with LOCK:
                STATE.update(mode=req.get("mode", "ok"), endpoint=req.get("endpoint"),
                             count=req.get("count"), delay=float(req.get("delay", STATE["delay"])))
            return self._send(200, {"ok": True})
        return self._send(404, {"error": "unknown control path"})

    def _handle(self, method):
        path = self.path.split("?")[0]
        if path.startswith("/__mock/"):
            return self._control(method, path)
        if not path.startswith(PREFIX) or path[len(PREFIX):] not in ENDPOINTS:
            return self._send(404, {"Success": False})
        endpoint = path[len(PREFIX):]
        raw = self._body() if method == "POST" else b""
        with LOCK:
            STATE["stats"][endpoint] = STATE["stats"].get(endpoint, 0) + 1
        mode, delay = scenario_for(endpoint)
        if mode == "rate_limit":
            return self._send(429, {"Success": False}, headers={"Retry-After": "1"})
        if mode == "server_error":
            return self._send(500, {"Success": False})
        if mode == "slow":
            time.sleep(delay)
        if mode == "bad_json":
            return self._send(200, raw=b"<html>not json</html>")
        if mode == "bad_success":
            return self._send(200, {"Success": "true"})
        if mode == "failure":
            return self._send(200, {"Success": False, "Message": "mock failure"})
        if mode == "redirect":
            return self._send(302, raw=b"", headers={"Location": "http://127.0.0.1:1/never"})
        if mode == "drop":
            self.close_connection = True
            return
        if not self.headers.get("Ocp-Apim-Subscription-Key"):
            return self._send(401, {"Success": False})
        if method == "GET":  # GetSubjectCategories
            return self._send(200, CATEGORIES)
        try:
            body = json.loads(raw.decode("utf-8"))
            assert isinstance(body, dict)
        except Exception:
            return self._send(400, {"Success": False})
        if endpoint == "Login":
            with LOCK:
                token = "mock-token-%d" % (len(STATE["tokens"]) + 1)
                STATE["tokens"].add(token)
            return self._send(200, {"Success": True, "Token": token})
        if endpoint == "createupdatecontact":
            return self._send(200, {"Success": True, "ContactIdInCRM": "mock-contact-1"})
        if endpoint == "CreateCase":
            key = hashlib.sha256(json.dumps({k: v for k, v in body.items() if k != "Token"},
                                            sort_keys=True).encode()).hexdigest()
            with LOCK:
                if mode == "duplicate" and key in STATE["seen_cases"]:
                    return self._send(200, {"Success": False, "Message": "duplicate (assumed)"})
                n = STATE["next_case"]; STATE["next_case"] += 1
                STATE["seen_cases"][key] = n
            return self._send(200, {"Success": True, "CaseIdInCRM": "mock-case-%d" % n,
                                    "WebCaseIdInCRM": "0000-%07d" % n})
        if endpoint == "AddFileToCase":
            size = len(body.get("fileContent", ""))
            return self._send(200, {"Success": True, "ReceivedBase64Chars": size})
        self._send(404, {"Success": False})

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


def make_server(port=0, scenario="ok", delay=3.0):
    reset(scenario, delay)
    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=8106)
    ap.add_argument("--scenario", default="ok")
    ap.add_argument("--delay", type=float, default=3.0)
    a = ap.parse_args()
    srv = make_server(a.port, a.scenario, a.delay)
    print("mock municipal API on http://127.0.0.1:%d%s" % (srv.server_address[1], PREFIX), flush=True)
    srv.serve_forever()
