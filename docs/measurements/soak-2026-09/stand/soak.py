"""The load for the booblik soak: one producer and one consumer, on the published client.

Deliberately modest — about fifty records a second of a kilobyte each. This stand has two cores and
k0s beside it, so it cannot say anything about throughput; what it can say is whether anything
grows, drifts or stops over a hundred hours. The load is close in shape to the deployment that
produced issue #15: ninety producers at three events per five seconds.

Using the **published** `booblik` from PyPI rather than the sources next door, so a hundred hours of
it is also a hundred hours of the artefact a user installs.

Counters go to a JSON file the sampler reads, because a soak that reports only at the end reports
nothing when it dies in the middle.
"""

import json
import os
import threading
import time

from booblik import Connection
from booblik.errors import BrokerError, Code

BROKER = os.environ.get("BOOBLIK_BROKER", "127.0.0.1:9092")
TOPIC = os.environ.get("SOAK_TOPIC", "soak")
RATE = float(os.environ.get("SOAK_RATE", "50"))
SIZE = int(os.environ.get("SOAK_RECORD_BYTES", "1024"))
STATE = os.environ.get("SOAK_STATE", "/state/load.json")

state = {
    "started": time.time(),
    "produced": 0,
    "produce_errors": 0,
    "last_produce_error": None,
    "consumed": 0,
    "fetch_errors": 0,
    "last_fetch_error": None,
    "resets": 0,
    "position": 0,
    "lag": 0,
    "partitions": 0,
}
lock = threading.Lock()


def dump() -> None:
    while True:
        with lock:
            snapshot = dict(state, uptime=time.time() - state["started"])
        tmp = STATE + ".tmp"
        with open(tmp, "w") as out:
            json.dump(snapshot, out)
        os.replace(tmp, STATE)
        time.sleep(5)


def produce() -> None:
    payload = bytes(range(256)) * (SIZE // 256)
    interval = 1.0 / RATE
    while True:
        try:
            with Connection.connect(BROKER, timeout=30) as connection:
                topic = connection.topic(TOPIC)
                with lock:
                    state["partitions"] = len(topic.partitions)
                while True:
                    # A key per second, so records spread across partitions the way the sample does
                    # rather than all landing in one.
                    topic.send(payload, key=f"user-{int(time.time()) % 16}".encode())
                    with lock:
                        state["produced"] += 1
                    time.sleep(interval)
        except Exception as failure:
            with lock:
                state["produce_errors"] += 1
                state["last_produce_error"] = f"{type(failure).__name__}: {failure}"
            time.sleep(2)


def consume() -> None:
    position = None
    while True:
        try:
            with Connection.connect(BROKER, timeout=60) as connection:
                info = connection.metadata(TOPIC)[TOPIC][0]
                if position is None:
                    position = info.log_start_offset
                consumer = connection.consumer(TOPIC, 0, position, max_wait_millis=5_000)
                while True:
                    records = consumer.poll()
                    with lock:
                        state["consumed"] += len(records)
                        state["position"] = consumer.position
                        state["lag"] = consumer.lag
                    position = consumer.position
        except BrokerError as refusal:
            # Retention moving past a reader is a legitimate outcome, not a fault: the log start has
            # gone beyond where this one had got to. Counted rather than hidden — if it happens at
            # this rate, something is wrong with the sizing rather than with the broker.
            if refusal.code == Code.OFFSET_OUT_OF_RANGE:
                position = None
                with lock:
                    state["resets"] += 1
            else:
                with lock:
                    state["fetch_errors"] += 1
                    state["last_fetch_error"] = refusal.code.name
            time.sleep(2)
        except Exception as failure:
            with lock:
                state["fetch_errors"] += 1
                state["last_fetch_error"] = f"{type(failure).__name__}: {failure}"
            time.sleep(2)


threading.Thread(target=dump, daemon=True).start()
threading.Thread(target=produce, daemon=True).start()
consume()
