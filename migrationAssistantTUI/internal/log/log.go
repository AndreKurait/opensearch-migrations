// Package log centralizes file-only structured logging for the TUI.
//
// Per PLAN §6 — anything that writes to stdout/stderr in TUI mode corrupts
// the alt-screen. Setup() wires slog to a JSON file BEFORE tea.NewProgram
// runs, and `forbidigo` lints fmt.Print* out of internal/ui (PLAN §6.3).
//
// Headless mode (--no-tui, future): swap to a stderr text handler. The
// Setup signature stays stable.
package log

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
)

// Setup wires the default slog logger to a TUI-safe file destination.
// Returns the *os.File so callers can defer Close after Program.Run().
//
// Behavior:
//   - File path: <UserCacheDir>/opensearch-migration-assistant/log/tui.log
//   - Truncate-on-launch (we keep last-launch logs for support; rotation
//     is a future concern — PLAN §6.1)
//   - Level: Info (or Debug if verbose=true OR LOG_LEVEL=debug)
//   - Handler: slog JSON
//
// CAUTION: never call this twice in one process — slog.SetDefault clobbers.
func Setup(verbose bool) (*os.File, error) {
	dir, err := os.UserCacheDir()
	if err != nil {
		// Fallback so the TUI can still launch when XDG dirs are missing.
		dir = filepath.Join(os.TempDir(), "ma-cache")
	}
	logDir := filepath.Join(dir, "opensearch-migration-assistant", "log")
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		return nil, fmt.Errorf("mkdir %s: %w", logDir, err)
	}
	path := filepath.Join(logDir, "tui.log")
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	level := slog.LevelInfo
	if verbose || os.Getenv("LOG_LEVEL") == "debug" {
		level = slog.LevelDebug
	}
	h := slog.NewJSONHandler(f, &slog.HandlerOptions{Level: level, AddSource: true})
	slog.SetDefault(slog.New(h))
	slog.Info("log.setup", "path", path, "verbose", verbose)
	return f, nil
}

// Errorf is a thin wrapper that lets callers log without importing slog.
// Mirrors the convenience of fmt.Errorf without the screen-corrupting risk.
func Errorf(format string, args ...any) {
	slog.Error(fmt.Sprintf(format, args...))
}

// Panic logs a panic value plus stack trace under the "ui.panic" event.
// Called from the top-level recover in cmd/tui/main.go (PLAN §6.4).
func Panic(value any, stack []byte) {
	slog.Error("ui.panic", "value", fmt.Sprintf("%v", value), "stack", string(stack))
}
