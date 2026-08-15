"""Drives the client under test.

The contract is a command-line one, and deliberately the smallest thing that can work in every
language booblik might get a client in. An HTTP control API would mean every client author writes a
server before writing a producer; a library the harness links against would not be language-neutral
at all. A program that reads argv and writes lines is about fifty lines in anything, including C.

## The contract

The client is invoked as `<command> <verb> [args...]`, with the broker in the environment:

    BOOBLIK_BROKER=host:port

It writes `key=value` lines to stdout, one per line, and repeated keys mean a list. Anything on
stderr is diagnostics and is shown only when a check fails.

**Exit code 0 means the verb was carried out**, including when the broker refused the request —
a refusal is a result, and it is reported as `error=UNKNOWN_TOPIC_OR_PARTITION`. A non-zero exit
means the client itself failed, and that is never an expected outcome of any check here.

### Verbs

| verb | arguments | answers |
|---|---|---|
| `capabilities` | — | `roles=producer[,consumer]`, `name=<what to call it>` |
| `metadata` | `<topic>` | `partition=<id> <logStartOffset> <highWatermark>`, repeated |
| `produce` | `<topic> <partition> <ack> <hex>[,<hex>…]` | `baseOffset=`, `logEndOffset=` |
| `produce-keyed` | `<topic> <keyHex> <payloadHex>` | `partition=`, `baseOffset=` |
| `fetch` | `<topic> <partition> <offset> <maxBytes>` | `highWatermark=`, `record=<hex>` repeated |

`ack` is `none`, `written` or `forced`. `produce` with `none` must answer nothing and **return** —
the broker sends no response, and waiting for one is the most common way to write a first producer.

`produce-keyed` is where the partitioner is exercised for real: the client picks the partition from
the key by itself, and the harness checks that choice against the golden vectors and against where
the record actually landed.

A producer-only client may exit non-zero on `fetch`; the harness never calls it unless
`capabilities` said `consumer`.
"""

import os
import shlex
import subprocess


class ClientError(Exception):
    pass


class Client:
    def __init__(self, command: str, broker: str, timeout: float = 20.0):
        self.argv = shlex.split(command)
        self.broker = broker
        self.timeout = timeout
        self.name = command
        self.roles = set()

    def call(self, *args, timeout: float = None) -> dict:
        """Runs one verb and returns its answer as `{key: [values]}`.

        The timeout is not belt-and-braces: `produce … none` is checked precisely by whether the
        client comes back, and a client that waits for a response that is never coming would
        otherwise hang the whole run instead of failing one check.
        """
        environment = dict(os.environ, BOOBLIK_BROKER=self.broker)
        try:
            finished = subprocess.run(
                self.argv + [str(a) for a in args],
                capture_output=True,
                text=True,
                timeout=timeout or self.timeout,
                env=environment,
            )
        except subprocess.TimeoutExpired:
            raise ClientError(
                f"`{' '.join(str(a) for a in args)}` did not return within "
                f"{timeout or self.timeout:g}s — the client is waiting for something"
            )

        if finished.returncode != 0:
            raise ClientError(
                f"`{' '.join(str(a) for a in args)}` exited {finished.returncode}\n"
                f"{indent(finished.stderr or finished.stdout)}"
            )

        answer = {}
        for line in finished.stdout.splitlines():
            line = line.strip()
            if not line or "=" not in line:
                continue
            key, value = line.split("=", 1)
            answer.setdefault(key.strip(), []).append(value.strip())
        return answer

    def one(self, *args, **kwargs):
        """A verb whose answer is expected to be a single value per key."""
        return {key: values[0] for key, values in self.call(*args, **kwargs).items()}

    def declares(self, role: str) -> bool:
        return role in self.roles

    def load_capabilities(self):
        answer = self.one("capabilities")
        if "roles" not in answer:
            raise ClientError("`capabilities` did not answer `roles=`")
        self.roles = {role.strip() for role in answer["roles"].split(",") if role.strip()}
        self.name = answer.get("name", self.name)
        unknown = self.roles - {"producer", "consumer"}
        if unknown:
            raise ClientError(f"unknown role(s) declared: {', '.join(sorted(unknown))}")
        if not self.roles:
            raise ClientError("no roles declared — there is nothing to check")


def indent(text: str) -> str:
    return "\n".join(f"      {line}" for line in (text or "").strip().splitlines())
