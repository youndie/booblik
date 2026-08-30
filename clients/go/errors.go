package booblik

import "fmt"

// Code is why the broker refused a request. The values are on the wire and must not be renumbered;
// see docs/api/protocol-wire.md §5.
type Code int16

const (
	CodeNone                    Code = 0
	CodeUnknownTopicOrPartition Code = 1
	CodeOffsetOutOfRange        Code = 2
	CodeRecordTooLarge          Code = 3
	CodeUnsupportedVersion      Code = 4
	CodeCorruptRequest          Code = 5
	// CodePartitionUnavailable is the partition's writer having died — a full volume being the
	// case it was added for. Retrying does not help: the writer is gone for the life of the
	// broker process, and reads from the same partition still work.
	CodePartitionUnavailable Code = 6
)

func (c Code) String() string {
	switch c {
	case CodeNone:
		return "NONE"
	case CodeUnknownTopicOrPartition:
		return "UNKNOWN_TOPIC_OR_PARTITION"
	case CodeOffsetOutOfRange:
		return "OFFSET_OUT_OF_RANGE"
	case CodeRecordTooLarge:
		return "RECORD_TOO_LARGE"
	case CodeUnsupportedVersion:
		return "UNSUPPORTED_VERSION"
	case CodeCorruptRequest:
		return "CORRUPT_REQUEST"
	case CodePartitionUnavailable:
		return "PARTITION_UNAVAILABLE"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", int16(c))
	}
}

// BrokerError is a refusal from the broker, as opposed to anything going wrong with the connection.
//
// A refusal is a result and not a transport failure: the connection stays usable, because framing
// was intact — the broker understood the request and declined it. Only a frame length outside the
// allowed range closes a connection.
type BrokerError struct {
	Code Code
}

func (e *BrokerError) Error() string {
	return "booblik: broker refused the request: " + e.Code.String()
}

// Is compares by code, so callers can write errors.Is(err, booblik.ErrUnknownTopicOrPartition)
// without unwrapping to reach a field.
func (e *BrokerError) Is(target error) bool {
	other, ok := target.(*BrokerError)
	return ok && other.Code == e.Code
}

// Sentinels for errors.Is. Each is the error the broker returns with that code.
var (
	ErrUnknownTopicOrPartition = &BrokerError{Code: CodeUnknownTopicOrPartition}
	ErrOffsetOutOfRange        = &BrokerError{Code: CodeOffsetOutOfRange}
	ErrRecordTooLarge          = &BrokerError{Code: CodeRecordTooLarge}
	ErrUnsupportedVersion      = &BrokerError{Code: CodeUnsupportedVersion}
	ErrCorruptRequest          = &BrokerError{Code: CodeCorruptRequest}
	ErrPartitionUnavailable    = &BrokerError{Code: CodePartitionUnavailable}
)
