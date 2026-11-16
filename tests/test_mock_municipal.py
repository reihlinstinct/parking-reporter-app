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
        data = json.dumps(payload or {}).encode()
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
        self.assertTrue(json.loads(b)["WebCaseIdInCRM"].startswith("0000-"))

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

    def test_drop_closes_connection(self):
        self.ctl("scenario", {"mode": "drop"})
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        c.request("POST", PREFIX + "Login", b"{}", {"Ocp-Apim-Subscription-Key": "k"})
        with self.assertRaises(Exception):
            c.getresponse()


if __name__ == "__main__":
    unittest.main()
