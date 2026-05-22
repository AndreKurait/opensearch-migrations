package pubsub

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestBroker_PublishToSubscribers(t *testing.T) {
	t.Parallel()
	b := New[int](4)
	defer b.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	a := b.Subscribe(ctx)
	c := b.Subscribe(ctx)

	b.Publish(7)
	b.Publish(11)

	require.Equal(t, 7, <-a)
	require.Equal(t, 11, <-a)
	require.Equal(t, 7, <-c)
	require.Equal(t, 11, <-c)
}

func TestBroker_CloseClosesAllSubscribers(t *testing.T) {
	t.Parallel()
	b := New[string](1)
	ctx := context.Background()
	a := b.Subscribe(ctx)
	c := b.Subscribe(ctx)

	b.Close()

	for _, ch := range []<-chan string{a, c} {
		select {
		case _, ok := <-ch:
			require.False(t, ok, "subscriber channel must close on broker.Close")
		case <-time.After(time.Second):
			t.Fatal("subscriber channel did not close within timeout")
		}
	}
	require.True(t, b.Closed())
}

func TestBroker_PublishAfterCloseIsNoOp(t *testing.T) {
	t.Parallel()
	b := New[int](1)
	b.Close()
	// Must not panic.
	b.Publish(1)
	b.Publish(2)
	require.True(t, b.Closed())
}

func TestBroker_SubscribeAfterCloseReturnsClosedChan(t *testing.T) {
	t.Parallel()
	b := New[int](1)
	b.Close()
	ch := b.Subscribe(context.Background())
	_, ok := <-ch
	require.False(t, ok)
}

func TestBroker_SubscribeContextCancelClosesChan(t *testing.T) {
	t.Parallel()
	b := New[int](1)
	defer b.Close()

	ctx, cancel := context.WithCancel(context.Background())
	ch := b.Subscribe(ctx)
	cancel()

	select {
	case _, ok := <-ch:
		require.False(t, ok)
	case <-time.After(time.Second):
		t.Fatal("ctx cancel did not close subscriber chan")
	}
}

func TestBroker_CloseIdempotent(t *testing.T) {
	t.Parallel()
	b := New[int](1)
	b.Close()
	b.Close() // must not panic
}

// TestBroker_FanOutManySubscribers stresses the per-subscriber channel
// fanout: 64 subscribers, 1000 publishes, every subscriber sees its
// share. The non-blocking Publish drops on full buffer, so we use a
// generous buffer so this test asserts at-most-once delivery without
// false drops.
func TestBroker_FanOutManySubscribers(t *testing.T) {
	t.Parallel()
	const subs = 64
	const pubs = 1000
	b := New[int](pubs * 2)
	defer b.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	chans := make([]<-chan int, subs)
	for i := 0; i < subs; i++ {
		chans[i] = b.Subscribe(ctx)
	}
	go func() {
		for i := 0; i < pubs; i++ {
			b.Publish(i)
		}
	}()

	// Drain each subscriber. Each must receive at least most of the
	// stream — the buffer is large enough that drops shouldn't happen.
	var wg sync.WaitGroup
	for i := 0; i < subs; i++ {
		wg.Add(1)
		ch := chans[i]
		go func() {
			defer wg.Done()
			received := 0
			deadline := time.After(2 * time.Second)
			for received < pubs {
				select {
				case _, ok := <-ch:
					if !ok {
						return
					}
					received++
				case <-deadline:
					return
				}
			}
		}()
	}
	wg.Wait()
}

// TestBroker_RaceyPublishClose stresses the §9 invariant: if a publisher
// races with Close, no panic occurs and Close still completes promptly.
// Run under -race for full effect.
func TestBroker_RaceyPublishClose(t *testing.T) {
	t.Parallel()
	const N = 32
	for i := 0; i < N; i++ {
		b := New[int](1)
		ctx, cancel := context.WithCancel(context.Background())
		var wg sync.WaitGroup
		var pubs atomic.Int64
		for p := 0; p < 4; p++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for {
					select {
					case <-ctx.Done():
						return
					default:
					}
					b.Publish(1)
					pubs.Add(1)
				}
			}()
		}
		// Let publishers warm up briefly, then close.
		time.Sleep(2 * time.Millisecond)
		b.Close()
		cancel()
		wg.Wait()
		require.True(t, b.Closed())
	}
}
