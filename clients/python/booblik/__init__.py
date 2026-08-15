"""A client for booblik, a message broker on an append-only log.

Speaks PRODUCE, FETCH and METADATA. The two halves are not the same size: a publisher that gets
something wrong is told so by the broker, while a consumer that gets something wrong returns
plausible bytes or stops quietly, so the reading half carries the checksum, the truncated tail and
the position.

The protocol is docs/api/protocol-wire.md; where this disagrees with it, this is wrong.
"""

from .connection import AckPolicy, Connection, Fetched, PartitionInfo, ProduceResult, Topic
from .consumer import DEFAULT_MAX_BYTES, DEFAULT_MAX_WAIT_MILLIS, Consumer
from .crc32c import crc32c
from .errors import (
    BrokerError,
    Code,
    CorruptRecordError,
    ProtocolError,
    RecordExceedsMaxBytesError,
)
from .partition import fnv1a32, partition_for
from .producer import OFFSET_UNKNOWN, Producer, ProducerConfig

__all__ = [
    "DEFAULT_MAX_BYTES",
    "DEFAULT_MAX_WAIT_MILLIS",
    "OFFSET_UNKNOWN",
    "AckPolicy",
    "BrokerError",
    "Code",
    "Connection",
    "Consumer",
    "CorruptRecordError",
    "Fetched",
    "PartitionInfo",
    "ProduceResult",
    "Producer",
    "ProducerConfig",
    "ProtocolError",
    "RecordExceedsMaxBytesError",
    "Topic",
    "crc32c",
    "fnv1a32",
    "partition_for",
]

__version__ = "0.1.0"
