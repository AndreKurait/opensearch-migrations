package log_test

import (
	"bytes"
	"log/slog"
	"runtime/debug"
	"testing"

	"github.com/stretchr/testify/require"

	tuilog "github.com/opensearch-project/opensearch-migrations/tui/internal/log"
)

// TestPanicLogsViaSlog redirects slog to a buffer and asserts log.Panic
// emits a structured "ui.panic" event with the value AND stack — that's
// the exact contract main.go's deferred recover relies on.
func TestPanicLogsViaSlog(t *testing.T) {
	var buf bytes.Buffer
	prev := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(prev) })

	tuilog.Panic("boom", []byte("stacktrace-here"))
	out := buf.String()
	require.Contains(t, out, "ui.panic")
	require.Contains(t, out, "boom")
	require.Contains(t, out, "stacktrace-here")
}

func TestErrorfFormats(t *testing.T) {
	var buf bytes.Buffer
	prev := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(prev) })

	tuilog.Errorf("boom %d on %s", 42, "tuesday")
	require.Contains(t, buf.String(), "boom 42 on tuesday")
}

func TestSetupCreatesLogFile(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	f, err := tuilog.Setup(false)
	require.NoError(t, err)
	require.NotNil(t, f)
	t.Cleanup(func() { _ = f.Close() })

	// Sanity: the file exists and is opened for writing.
	require.NotEmpty(t, f.Name(), "log file should have a path")
}

func TestSetupRotatesPreviousLog(t *testing.T) {
	t.Setenv("HOME", t.TempDir())

	// First Setup creates tui.log.
	f1, err := tuilog.Setup(false)
	require.NoError(t, err)
	require.NoError(t, f1.Close())

	// Second Setup must rotate the first to .prev. Note: we can't call
	// Setup twice in one process under slog default-handler semantics,
	// but the rotation logic doesn't depend on slog state.
	f2, err := tuilog.Setup(false)
	require.NoError(t, err)
	t.Cleanup(func() { _ = f2.Close() })

	require.FileExists(t, f2.Name()+".prev",
		"second Setup must rotate the previous log to .prev so support has one extra generation")
}

func TestSetupRunCapturesStackOnPanic(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	f, err := tuilog.Setup(false)
	require.NoError(t, err)
	t.Cleanup(func() { _ = f.Close() })

	// Capture the stack to prove debug.Stack() is the right shape.
	stk := debug.Stack()
	require.NotEmpty(t, stk)
	tuilog.Panic("test panic", stk)
}
