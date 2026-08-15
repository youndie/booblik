package booblik

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

// OffsetUnknown is what a Promise carries when the batch went out under AckNone. The record was
// sent; no offset exists, because none is assigned until the writer reaches the batch.
const OffsetUnknown int64 = -1

// ErrProducerClosed is returned by Send and Flush after Close.
var ErrProducerClosed = errors.New("booblik: producer is closed")

// ProducerConfig tunes the accumulator. The zero value is not useful; use DefaultProducerConfig.
type ProducerConfig struct {
	// MaxBatchSize is how many records go in one request. Reached first, the batch goes at once.
	MaxBatchSize int
	// Linger is how long an incomplete batch waits for company.
	//
	// Zero is not the fast setting. It sends every record on its own, which the broker's own
	// measurements put at 80 592 records/s against 4 335 482 for batches of a hundred — the
	// accumulator is the single largest performance factor in this project, an order of magnitude
	// more than the choice of write path. Non-zero trades a bounded amount of latency for it.
	Linger time.Duration
	Ack    AckPolicy
	// RequestTimeout bounds one delivery, so a broker that stops answering fails the records
	// waiting on it instead of hanging the accumulator for ever.
	RequestTimeout time.Duration
}

// DefaultProducerConfig matches the JVM client's defaults, so the same program batches the same way
// whichever client it uses.
func DefaultProducerConfig() ProducerConfig {
	return ProducerConfig{
		MaxBatchSize:   100,
		Linger:         5 * time.Millisecond,
		Ack:            AckWritten,
		RequestTimeout: 30 * time.Second,
	}
}

// Promise is where a record's offset arrives. Safe to await from several goroutines and more than
// once.
type Promise struct {
	done   chan struct{}
	offset int64
	err    error
}

func newPromise() *Promise { return &Promise{done: make(chan struct{})} }

func (p *Promise) complete(offset int64, err error) {
	p.offset, p.err = offset, err
	close(p.done)
}

// Await blocks until the broker has answered for this record, or ctx is done.
func (p *Promise) Await(ctx context.Context) (int64, error) {
	select {
	case <-p.done:
		return p.offset, p.err
	case <-ctx.Done():
		return 0, ctx.Err()
	}
}

type batchKey struct {
	topic     string
	partition int32
}

type batch struct {
	records  [][]byte
	promises []*Promise
}

type command struct {
	topic     string
	partition int32
	record    []byte
	promise   *Promise
	// flush is closed once everything queued has been delivered. Non-nil marks this as a flush
	// rather than an append.
	flush chan struct{}
}

// Producer accumulates records and sends them in batches.
//
// It owns its Conn: one goroutine holds the pending records and is the only thing that writes to
// the socket. **Do not use that Conn directly while a Producer has it** — requests and responses
// are matched in order, and a second writer would take somebody else's answer.
//
// Records for different partitions accumulate separately and go out as separate requests, because
// a request addresses one partition — a partition being what has one writer.
type Producer struct {
	conn    *Conn
	config  ProducerConfig
	mailbox chan command
	// closing is what Close signals with. The mailbox itself is **never** closed, and that is a
	// fix rather than a style: Send selects between writing to the mailbox and observing shutdown,
	// Go picks at random among the ready cases, and a send on a closed channel counts as ready and
	// panics. Closing the mailbox therefore made any Send racing a Close a crash in the caller's
	// process — found by running four client gates back to back, which moved the timing enough for
	// it to land.
	closing chan struct{}
	done    chan struct{}
	closed  atomic.Bool
	once    sync.Once
}

// NewProducer starts the accumulator. Close it to stop the goroutine and flush what is queued.
func NewProducer(conn *Conn, config ProducerConfig) *Producer {
	producer := &Producer{
		conn:    conn,
		config:  config,
		mailbox: make(chan command),
		closing: make(chan struct{}),
		done:    make(chan struct{}),
	}
	go producer.run()
	return producer
}

// Send queues a record and returns where its offset will arrive.
//
// The record is not on the wire when this returns — that is the point. Await the promise to know it
// landed, or call Flush to push everything queued.
func (p *Producer) Send(ctx context.Context, topic string, partition int32, record []byte) (*Promise, error) {
	if p.closed.Load() {
		return nil, ErrProducerClosed
	}

	promise := newPromise()
	select {
	// The mailbox is unbuffered, so this completing means the loop has the record and will deliver
	// it. Anything Send reports as accepted is therefore never dropped by a concurrent Close.
	case p.mailbox <- command{topic: topic, partition: partition, record: record, promise: promise}:
		return promise, nil
	case <-p.done:
		return nil, ErrProducerClosed
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Flush sends everything queued and waits for the broker to answer all of it.
func (p *Producer) Flush(ctx context.Context) error {
	signal := make(chan struct{})
	select {
	case p.mailbox <- command{flush: signal}:
	case <-p.done:
		return ErrProducerClosed
	case <-ctx.Done():
		return ctx.Err()
	}

	select {
	case <-signal:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Close flushes what is queued and stops the accumulator. It does not close the Conn.
func (p *Producer) Close() error {
	p.once.Do(func() {
		p.closed.Store(true)
		close(p.closing)
	})
	<-p.done
	return nil
}

func (p *Producer) run() {
	pending := make(map[batchKey]*batch)

	var timer *time.Timer
	var fire <-chan time.Time

	stopTimer := func() {
		if timer != nil {
			timer.Stop()
			timer, fire = nil, nil
		}
	}

	for {
		// `select` commits to exactly one case, which is the whole reason this loop is simple here.
		// The equivalent on the JVM was written twice as "receive with a timeout", and both times
		// it lost records: a timeout cancels the receive, and a cancelled receive can take an
		// element off the channel and then drop it, after which whoever was waiting on that
		// record's offset waits for ever while the accumulator serves everybody else. Nothing in
		// this select can take a record and then not have it.
		select {
		case <-p.closing:
			// Close is a flush: whatever the loop already accepted still goes out. Dropping it
			// would make every clean shutdown a silent data loss.
			stopTimer()
			p.deliver(pending)
			close(p.done)
			return

		case cmd := <-p.mailbox:
			if cmd.flush != nil {
				stopTimer()
				p.deliver(pending)
				close(cmd.flush)
				continue
			}

			key := batchKey{cmd.topic, cmd.partition}
			pendingBatch := pending[key]
			if pendingBatch == nil {
				pendingBatch = &batch{}
				pending[key] = pendingBatch
			}
			pendingBatch.records = append(pendingBatch.records, cmd.record)
			pendingBatch.promises = append(pendingBatch.promises, cmd.promise)

			// The window is measured from the **first** record of the batch and is never
			// restarted. Timing from the last would let a steady trickle postpone the send
			// indefinitely, turning a latency bound into a latency hope.
			if timer == nil {
				timer = time.NewTimer(p.config.Linger)
				fire = timer.C
			}
			if len(pendingBatch.records) >= p.config.MaxBatchSize {
				stopTimer()
				p.deliver(pending)
			}

		case <-fire:
			timer, fire = nil, nil
			p.deliver(pending)
		}
	}
}

func (p *Producer) deliver(pending map[batchKey]*batch) {
	for key, pendingBatch := range pending {
		delete(pending, key)

		ctx, cancel := context.WithTimeout(context.Background(), p.config.RequestTimeout)
		result, err := p.conn.Produce(ctx, key.topic, key.partition, pendingBatch.records, p.config.Ack)
		cancel()

		for i, promise := range pendingBatch.promises {
			switch {
			case err != nil:
				promise.complete(0, err)
			case result == nil:
				// AckNone: sent, and there is no offset to report.
				promise.complete(OffsetUnknown, nil)
			default:
				// One request is written by one call, so the records are contiguous from the base.
				promise.complete(result.BaseOffset+int64(i), nil)
			}
		}
	}
}
