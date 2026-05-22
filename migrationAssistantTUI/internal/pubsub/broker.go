// Package pubsub provides a generic, drain-safe broker that fans out
// typed events to multiple subscribers.
//
// Used per PLAN §4.3 / §9 to bridge service goroutines to the UI loop:
// app publishes domain events; the UI subscribes once in Init and pumps
// them through Update via the canonical "long-running channel" cmd
// (`waitForEvent`), which detects close via the `, ok` idiom.
//
// Contract:
//
//   - Publish is non-blocking and idempotent after Close.
//   - Subscribe returns a channel that closes when the subscriber's ctx
//     is canceled OR the broker is closed (whichever first).
//   - Close is idempotent; safe to call from a defer.
//
// Lifecycle (PLAN §9.2):
//
//	cancel(ctx) → wg.Wait() for publishers → broker.Close()
//
// Publishers MUST follow the §9.3 select-on-ctx.Done pattern; otherwise a
// late publish after Close panics. The broker itself never panics.
package pubsub

import (
	"context"
	"sync"
)

// Broker is a typed in-memory pub-sub fanout.
//
// The zero value is unusable — call New[T](buf).
type Broker[T any] struct {
	mu     sync.RWMutex
	subs   map[chan T]struct{}
	done   chan struct{}
	closed bool
	buf    int
}

// New returns a broker whose per-subscriber channels are buffered to `buf`.
// A buffer of 0 forces synchronous delivery (back-pressure on Publish);
// 16 is a reasonable default for a TUI surface.
func New[T any](buf int) *Broker[T] {
	if buf < 0 {
		buf = 0
	}
	return &Broker[T]{
		subs: map[chan T]struct{}{},
		done: make(chan struct{}),
		buf:  buf,
	}
}

// Subscribe returns a receive channel that closes when ctx is canceled OR
// the broker is closed. Multiple Subscribe calls fan out independently.
//
// Returns a closed channel if the broker is already closed, so range-loops
// in subscribers exit immediately.
func (b *Broker[T]) Subscribe(ctx context.Context) <-chan T {
	ch := make(chan T, b.buf)
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		close(ch)
		return ch
	}
	b.subs[ch] = struct{}{}
	b.mu.Unlock()

	// Watcher: drop subscription when ctx ends.
	go func() {
		select {
		case <-ctx.Done():
		case <-b.done:
		}
		b.mu.Lock()
		if _, ok := b.subs[ch]; ok {
			delete(b.subs, ch)
			close(ch)
		}
		b.mu.Unlock()
	}()
	return ch
}

// Publish delivers v to all current subscribers. Non-blocking: if a
// subscriber's buffer is full, the message is dropped FOR THAT SUBSCRIBER
// (other subscribers still receive it). Calls after Close are no-ops.
func (b *Broker[T]) Publish(v T) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	if b.closed {
		return
	}
	for ch := range b.subs {
		select {
		case ch <- v:
		default:
			// Drop on full buffer rather than back-pressure publishers.
			// This is the canonical TUI tradeoff: missing a redraw event
			// is recoverable; deadlocking the program is not.
		}
	}
}

// Close ends all subscriptions. Idempotent.
func (b *Broker[T]) Close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return
	}
	b.closed = true
	close(b.done)
	for ch := range b.subs {
		close(ch)
		delete(b.subs, ch)
	}
}

// Closed reports whether Close has been called. Useful in tests.
func (b *Broker[T]) Closed() bool {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.closed
}
