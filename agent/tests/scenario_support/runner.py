"""Five-operation JSON scenario runner over real HTTP. Assertions never read manager state."""
import copy
import json
import re
import time
import uuid
from pathlib import Path

from scenario_support.fixture import TestDaemonFixture
from scenario_support.peer import ScriptedForgePeer

OPS = {
    "http": ({"op", "path", "body", "expect"}, {"save"}),
    "receipt": ({"op", "from", "status", "reason", "count", "expect"}, {"save"}),
    "advance": ({"op", "ms"}, set()),
    "tick": ({"op"}, set()),
    "restart": ({"op"}, set()),
}


class ScenarioFormatError(ValueError):
    pass


def pointer(value, path):
    if path == "":
        return value
    if not isinstance(path, str) or not path.startswith("/"):
        raise ScenarioFormatError("invalid JSON pointer: %r" % path)
    for token in path[1:].split("/"):
        if re.search(r"~(?![01])", token):
            raise ScenarioFormatError("invalid pointer escape")
        token = token.replace("~1", "/").replace("~0", "~")
        if isinstance(value, list):
            if not re.fullmatch(r"0|[1-9][0-9]*", token):
                raise ScenarioFormatError("invalid array index")
            value = value[int(token)]
        else:
            value = value[token]
    return value


def equal(actual, expected):
    # Python considers True == 1; the wire contract must not.
    if type(actual) is not type(expected):
        return False
    if isinstance(actual, dict):
        return actual.keys() == expected.keys() and all(equal(actual[k], expected[k]) for k in actual)
    if isinstance(actual, list):
        return len(actual) == len(expected) and all(equal(a, e) for a, e in zip(actual, expected))
    return actual == expected


def validate(document):
    if not isinstance(document, dict) or set(document) != {"scenarioVersion", "name", "steps"}:
        raise ScenarioFormatError("expected scenarioVersion, name, steps")
    if type(document["scenarioVersion"]) is not int or document["scenarioVersion"] != 1:
        raise ScenarioFormatError("unsupported scenarioVersion")
    if not isinstance(document["name"], str) or not re.fullmatch(r"[a-z0-9-]{1,80}", document["name"]):
        raise ScenarioFormatError("invalid scenario name")
    if not isinstance(document["steps"], list) or not 1 <= len(document["steps"]) <= 1000:
        raise ScenarioFormatError("expected 1..1000 steps")
    saves = set()
    for step in document["steps"]:
        if not isinstance(step, dict) or not isinstance(step.get("op"), str) or step["op"] not in OPS:
            raise ScenarioFormatError("unknown operation")
        required, optional = OPS[step["op"]]
        if not required <= step.keys() or not step.keys() <= required | optional:
            raise ScenarioFormatError("invalid fields for " + step["op"])
        if step["op"] in ("http", "receipt"):
            exp = step["expect"]
            if not isinstance(exp, dict) or not {"status"} <= exp.keys() <= {"status", "json"} \
                    or type(exp["status"]) is not int or not 100 <= exp["status"] <= 599 \
                    or not isinstance(exp.get("json", {}), dict):
                raise ScenarioFormatError("invalid expectation")
        if step["op"] == "advance" and (type(step["ms"]) is not int or step["ms"] < 0):
            raise ScenarioFormatError("invalid advance.ms")
        if "save" in step:
            key = step["save"]
            if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", key) or key in saves:
                raise ScenarioFormatError("invalid or duplicate save name")
            saves.add(key)


class ScenarioRunner:
    def __init__(self, output_root=None):
        self.output_root = Path(output_root) if output_root else \
            Path(__file__).resolve().parents[3] / ".tools" / "scenario-results"

    def resolve(self, value):
        if isinstance(value, dict):
            if "$ref" in value:
                if set(value) != {"$ref"} or not isinstance(value["$ref"], str):
                    raise ScenarioFormatError("invalid reference")
                name, sep, tail = value["$ref"].partition("/")
                try:
                    return copy.deepcopy(pointer(self.captures[name], "/" + tail if sep else ""))
                except (KeyError, IndexError, TypeError) as exc:
                    raise ScenarioFormatError("unresolved reference: " + value["$ref"]) from exc
            return {k: self.resolve(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self.resolve(v) for v in value]
        return value

    def run_file(self, path):
        return self.run(json.loads(Path(path).read_text(encoding="utf-8")))

    def run(self, document):
        validate(document)
        self.captures, self.transcript = {}, []
        self.artifact_dir = self.output_root / (document["name"] + "-" + uuid.uuid4().hex)
        self.artifact_dir.mkdir(parents=True)
        started = time.monotonic()
        summary = {"scenario": document["name"], "status": "failed", "stepsCompleted": 0}
        try:
            with TestDaemonFixture() as fixture:
                peer = ScriptedForgePeer(fixture)
                for index, step in enumerate(document["steps"], 1):
                    row = {"step": index, "op": step["op"], "virtualMs": fixture.clock.ms}
                    self.transcript.append(row)
                    try:
                        remaining = 30 - (time.monotonic() - started)
                        if remaining <= 0:
                            raise TimeoutError("scenario exceeded 30 seconds")
                        if step["op"] == "http":
                            path, body = step["path"], self.resolve(step["body"])
                        elif step["op"] == "receipt":
                            try:
                                view = self.captures[step["from"]]["body"]
                                body = peer.receipt(view, step["status"], step["reason"], step["count"])
                            except (KeyError, TypeError) as exc:
                                raise ScenarioFormatError("receipt requires a saved issuing view") from exc
                            path = "/v2/action-result"
                        else:
                            if step["op"] == "advance":
                                fixture.advance(step["ms"])
                            elif step["op"] == "tick":
                                fixture.tick()
                            else:
                                fixture.restart()
                            row["virtualMsAfter"] = fixture.clock.ms
                            summary["stepsCompleted"] = index
                            continue
                        row.update(path=path, request=copy.deepcopy(body))
                        response, duration = peer.post(path, body, timeout=min(2, remaining))
                        row.update(response=response, durationMs=round(duration * 1000, 3))
                        if time.monotonic() - started > 30:
                            raise TimeoutError("scenario exceeded 30 seconds")
                        expected = self.resolve(step["expect"])
                        row["expect"] = expected
                        if response["status"] != expected["status"]:
                            raise AssertionError("HTTP expected %s, got %s" % (expected["status"], response["status"]))
                        for path, value in expected.get("json", {}).items():
                            try:
                                actual = pointer(response["body"], path)
                            except (KeyError, IndexError, TypeError) as exc:
                                raise AssertionError("missing JSON pointer " + path) from exc
                            if not equal(actual, value):
                                raise AssertionError("%s: expected %r, got %r" % (path, value, actual))
                        if "save" in step:
                            self.captures[step["save"]] = copy.deepcopy(response)
                        summary["stepsCompleted"] = index
                    except Exception as exc:
                        row["error"] = str(exc)
                        message = "%s step %d (%s, t=%dms): %s; artifacts: %s" % (
                            document["name"], index, step["op"], fixture.clock.ms, exc, self.artifact_dir)
                        if isinstance(exc, ScenarioFormatError):
                            raise ScenarioFormatError(message) from exc
                        raise AssertionError(message) from exc
            summary["status"] = "passed"
            return copy.deepcopy(self.captures)
        except Exception as exc:
            summary["error"] = str(exc)
            raise
        finally:
            summary["durationMs"] = round((time.monotonic() - started) * 1000, 3)
            (self.artifact_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
            (self.artifact_dir / "transcript.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in self.transcript), encoding="utf-8")
