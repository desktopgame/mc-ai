import http.client
import json
import sys
import threading
import unittest
from contextlib import closing
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from daemon import Handler, ThreadingHTTPServer, turn

FIXTURES = Path(__file__).resolve().parents[2] / "protocol" / "examples"


class DaemonTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, body, path="/v1/turn", content_type="application/json"):
        with closing(http.client.HTTPConnection(*self.server.server_address, timeout=2)) as connection:
            connection.request("POST", path, body, {"Content-Type": content_type})
            response = connection.getresponse()
            return response.status, json.loads(response.read())

    def test_ping_over_http(self):
        status, body = self.request((FIXTURES / "ping-request.json").read_bytes())
        self.assertEqual(status, 200)
        self.assertEqual(body, json.loads((FIXTURES / "ping-response.json").read_text()))

    def test_invalid_events(self):
        valid = json.loads((FIXTURES / "ping-request.json").read_text())
        for payload in [None, [], {}, {**valid, "version": True}, {**valid, "version": 2},
                        {**valid, "event": {}}, {**valid, "state": []},
                        {**valid, "event": {**valid["event"], "text": "mine"}},
                        {**valid, "event": {**valid["event"], "player": ""}}]:
            with self.subTest(payload=payload):
                self.assertEqual(self.request(json.dumps(payload))[0], 400)

    def test_malformed_and_oversized(self):
        self.assertEqual(self.request(b"{invalid")[0], 400)
        self.assertEqual(self.request(b"\xff")[0], 400)
        self.assertEqual(self.request(b"x" * 8193)[0], 413)

    def test_wrong_route_and_content_type(self):
        self.assertEqual(self.request(b"{}", path="/v2/turn")[0], 404)
        self.assertEqual(self.request(b"{}", content_type="text/plain")[0], 415)


if __name__ == "__main__":
    unittest.main()
