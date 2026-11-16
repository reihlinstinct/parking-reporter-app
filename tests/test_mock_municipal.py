import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import json
import threading
import unittest
import urllib.error
import urllib.request
import http.client

from mock_municipal.server import make_server, PREFIX


class MockMunicipalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = make_server(0, "ok", 0.2)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        self.ctl("reset")

    def ctl(self, what, payload=None):
        if what == "stats":
            return json.load(urllib.request.urlopen(f"http://127.0.0.1:{self.port}/__mock/stats"))
        data = json.dumps({} if payload is None else payload).encode()
        urllib.request.urlopen(urllib.request.Request(
            f"http://127.0.0.1:{self.port}/__mock/{what}", data=data, method="POST"))

    def post(self, ep, body, key="k"):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{PREFIX}{ep}",
                                     data=json.dumps(body).encode(), method="POST",
                                     headers={"Ocp-Apim-Subscription-Key": key})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_happy_path(self):
        s, b = self.post("Login", {"UserName": "u", "Password": "p"})
        self.assertEqual((s, json.loads(b)["Success"]), (200, True))
        s, b = self.post("CreateCase", {"Token": "t", "x": 1})
        self.assertTrue(json.loads(b)["CaseNumberInCRM"].startswith("0000-"))

    def test_every_post_endpoint_rejects_non_object_json_without_side_effects(self):
        for endpoint in ["Login", "createupdatecontact", "CreateCase", "AddFileToCase"]:
            for body in [[], [1], None, True, 7, "synthetic"]:
                with self.subTest(endpoint=endpoint, body=body):
                    status, payload = self.post(endpoint, body)
                    self.assertEqual(status, 400)
                    self.assertIs(json.loads(payload)["Success"], False)
        # Rejected arrays must not mint tokens or consume case identifiers.
        self.assertEqual(json.loads(self.post("Login", {})[1])["Token"], "mock-token-1")
        self.assertEqual(json.loads(self.post("CreateCase", {})[1])["CaseIdInCRM"], "mock-case-1")

    def test_deeply_nested_json_returns_400(self):
        # Exceed the decoder's separate recursion limit on Python 3.12+ too.
        payload = b"[" * 100_000 + b"]" * 100_000
        with self.assertRaises(RecursionError):
            json.loads(payload)
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        try:
            c.request("POST", PREFIX + "CreateCase", payload,
                      {"Ocp-Apim-Subscription-Key": "k"})
            response = c.getresponse()
            self.assertEqual(response.status, 400)
            self.assertIs(json.loads(response.read())["Success"], False)
        finally:
            c.close()

    def test_missing_key_is_401(self):
        self.assertEqual(self.post("Login", {}, key="")[0], 401)

    def test_rate_limit_counts_one_request_only(self):
        self.ctl("scenario", {"mode": "rate_limit", "endpoint": "CreateCase", "count": 1})
        self.assertEqual(self.post("CreateCase", {"a": 1})[0], 429)
        self.assertEqual(self.ctl("stats")["requests"]["CreateCase"], 1)
        self.assertEqual(self.post("CreateCase", {"a": 2})[0], 200)  # count exhausted

    def test_bad_success_is_a_string(self):
        self.ctl("scenario", {"mode": "bad_success"})
        self.assertEqual(json.loads(self.post("Login", {})[1])["Success"], "true")

    def test_duplicate_assumed(self):
        self.ctl("scenario", {"mode": "duplicate"})
        self.assertTrue(json.loads(self.post("CreateCase", {"a": 1})[1])["Success"])
        self.assertFalse(json.loads(self.post("CreateCase", {"a": 1})[1])["Success"])
        self.assertTrue(json.loads(self.post("CreateCase", {"a": 2})[1])["Success"])

    def test_catalog_schema_matches_client(self):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{PREFIX}GetSubjectCategories?token=mock-token",
                                     headers={"Ocp-Apim-Subscription-Key": "k"})
        with urllib.request.urlopen(req) as r:
            tree = json.load(r)
        self.assertEqual(tree[0]["categoryName"], "בדיקה")
        self.assertTrue(all(isinstance(n["subjectCode"], str) for n in tree[0]["children"]))
        self.assertEqual(self.post("GetSubjectCategories", {})[0], 405)

    def test_error_modes_and_malformed_body(self):
        for mode, status in [("server_error", 500), ("failure", 200), ("bad_json", 200)]:
            self.ctl("scenario", {"mode": mode})
            code, body = self.post("CreateCase", {})
            self.assertEqual(code, status)
            if mode == "bad_json":
                with self.assertRaises(ValueError):
                    json.loads(body)
            else:
                self.assertFalse(json.loads(body)["Success"])
        self.ctl("reset")
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        c.request("POST", PREFIX + "Login", b"not json", {"Ocp-Apim-Subscription-Key": "k"})
        self.assertEqual(c.getresponse().status, 400)
        c.close()

    def test_invalid_control_is_rejected(self):
        for payload in [{"mode": "unknown"}, {"delay": -1}, {"count": -1}, {"mode": []}, {"endpoint": []}, []]:
            with self.assertRaises(urllib.error.HTTPError) as caught:
                self.ctl("scenario", payload)
            self.assertEqual(caught.exception.code, 400)

    def test_redirect_is_only_local_and_slow_is_configurable(self):
        self.ctl("scenario", {"mode": "redirect"})
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        c.request("POST", PREFIX + "Login", b"{}", {"Ocp-Apim-Subscription-Key": "k"})
        r = c.getresponse()
        self.assertEqual((r.status, r.getheader("Location")), (302, "http://127.0.0.1:1/never"))
        r.read(); c.close()
        self.ctl("scenario", {"mode": "slow", "delay": 0.05})
        import time
        start = time.monotonic()
        self.assertEqual(self.post("Login", {})[0], 200)
        self.assertGreaterEqual(time.monotonic() - start, 0.04)

    def test_drop_closes_connection(self):
        self.ctl("scenario", {"mode": "drop"})
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        c.request("POST", PREFIX + "Login", b"{}", {"Ocp-Apim-Subscription-Key": "k"})
        with self.assertRaises(Exception):
            c.getresponse()


if __name__ == "__main__":
    unittest.main()
