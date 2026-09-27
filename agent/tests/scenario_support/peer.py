"""Scripted protocol peer, not a Minecraft simulation or production validator."""
import copy
import json
import time
from http.client import HTTPConnection


class ScriptedForgePeer:
    def __init__(self, fixture):
        self.fixture = fixture

    def post(self, path, body, timeout=2):
        if not isinstance(path, str) or not path.startswith(("/v1/", "/v2/")) \
                or "?" in path or "#" in path:
            raise ValueError("scenario path must be a local v1/v2 endpoint")
        payload = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
        if len(payload) > 1024 * 1024:
            raise ValueError("scenario request exceeds 1 MiB")
        connection = HTTPConnection(*self.fixture.address, timeout=timeout)
        started = time.monotonic()
        try:
            connection.request("POST", path, payload, {"Content-Type": "application/json"})
            response = connection.getresponse()
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError("scenario response exceeds 1 MiB")
            return {"status": response.status, "body": json.loads(raw)}, time.monotonic() - started
        finally:
            connection.close()

    @staticmethod
    def receipt(view, status, reason, count):
        """Bind to the immutable issuing view, never a newer current revision."""
        action = view["action"]
        kind = action["type"]
        if kind == "pickup_target":
            field, name = "acquired", "item"
        elif kind == "mine_target":
            field, name = "destroyed", "block"
        else:
            raise ValueError("receipt requires a captured pickup_target or mine_target")
        return copy.deepcopy({
            "version": 2, "session": view["session"], "daemonEpoch": view["daemonEpoch"],
            "goalRevision": view["goalRevision"], "skillInstanceId": action["skillInstanceId"],
            "actionId": action["actionId"], "actionSequence": action["actionSequence"],
            "status": status, "reason": reason, field: {name: action[name], "count": count},
        })
