"""Real Daemon services and HTTP handler, with an explicitly driven test clock."""
import threading
import time
from http.server import ThreadingHTTPServer

from daemon import Handler
from execution_registry import ExecutionRegistry
from goals import GoalManager
from skills import SkillManager
from state_cache import StateCache
from terminal_events import TerminalEventStore


class ManualClock:
    def __init__(self):
        self.ms = 0

    def __call__(self):
        return self.ms / 1000.0

    def advance(self, ms):
        if type(ms) is not int or ms < 0:
            raise ValueError("advance.ms must be a nonnegative integer")
        self.ms += ms


class _Server(ThreadingHTTPServer):
    # Track handlers explicitly so teardown is bounded, including on assertion failure.
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.handlers = []

    def process_request(self, request, address):
        worker = threading.Thread(target=self.process_request_thread, args=(request, address),
                                  name="scenario-http-request", daemon=True)
        self.handlers.append(worker)
        worker.start()


class TestDaemonFixture:
    """A fresh isolated server per scenario. No production config, provider or ticker."""
    __test__ = False

    def __init__(self):
        self.server = None
        self.worker = None
        self.clock = ManualClock()

    @property
    def address(self):
        if self.server is None:
            raise RuntimeError("fixture is not running")
        return self.server.server_address

    def start(self):
        if self.server is not None:
            raise RuntimeError("fixture already running")
        self.clock = ManualClock()
        server = _Server()
        try:
            server.brain = None
            server.decisions = None
            server.states = StateCache(clock=self.clock)
            server.goals = GoalManager(server.states, None, clock=self.clock)
            server.registry = ExecutionRegistry(clock=self.clock)
            server.terminals = TerminalEventStore(clock=self.clock)
            server.skills = SkillManager(server.states, server.registry, clock=self.clock,
                                         terminal_store=server.terminals)
            server.shutdown_token = "scenario-only-no-http-shutdown"
            self.worker = threading.Thread(target=server.serve_forever,
                                           kwargs={"poll_interval": 0.01},
                                           name="scenario-http", daemon=True)
            self.server = server
            self.worker.start()
        except BaseException:
            server.server_close()
            self.server = None
            raise
        return self

    def tick(self):
        self.server.skills.tick()

    def advance(self, ms):
        self.clock.advance(ms)
        self.tick()

    def close(self):
        if self.server is None:
            return
        server, worker = self.server, self.worker
        self.server = None
        # shutdown waits for serve_forever; bound that wait too, rather than hanging unittest.
        stopper = threading.Thread(target=server.shutdown, daemon=True)
        stopper.start()
        deadline = time.monotonic() + 3
        try:
            for thread in [stopper, worker, *server.handlers]:
                thread.join(max(0, deadline - time.monotonic()))
            if any(t.is_alive() for t in [stopper, worker, *server.handlers]):
                raise RuntimeError("scenario HTTP teardown exceeded 3 seconds")
        finally:
            server.server_close()

    def restart(self):
        self.close()
        self.start()

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.close()
