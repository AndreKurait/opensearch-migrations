// Package workdir manages the named-subdir workspace per UX.md §0.4:
//
//	./opensearch-migration-<account>-<region>/
//	├── HANDOFF.md
//	├── .ma-state.json
//	├── .claude/skills/opensearch-migration/   (or .kiro/)
//	├── transcripts/
//	├── reports/
//	└── notes/
//
// The TUI owns HANDOFF.md, .ma-state.json, and .<agent>/. Everything
// else is the user's. We create the empty dirs once on first install
// but never modify their contents (locked **CC**: never auto-clean).
package workdir

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// State is the marker file we write after a successful install.
type State struct {
	SchemaVersion int       `json:"schema_version"`
	AccountID     string    `json:"aws_account"`
	Region        string    `json:"region"`
	MAVersion     string    `json:"ma_version"`
	TUIVersion    string    `json:"tui_version"`
	Stage         string    `json:"stage"`
	Mode          string    `json:"mode"` // "manual" | "agent"
	Agent         string    `json:"agent,omitempty"`
	Status        string    `json:"status"` // "in_progress" | "bootstrapped" | "installed" | "failed"
	InstalledAt   time.Time `json:"installed_at,omitempty"`
	UpdatedAt     time.Time `json:"updated_at"`
}

const (
	stateFile = ".ma-state.json"
	schemaVer = 1
)

// Resolve returns the canonical workdir path under cwd for (account, region).
//
// Returns an error if cwd resolves to one of the forbidden roots
// (UX.md §0.4 guard 1).
func Resolve(cwd, account, region string) (string, error) {
	if account == "" || region == "" {
		return "", errors.New("account and region are required")
	}
	abs, err := filepath.Abs(cwd)
	if err != nil {
		return "", fmt.Errorf("abs(%s): %w", cwd, err)
	}
	if err := guardCwd(abs); err != nil {
		return "", err
	}
	dir := fmt.Sprintf("opensearch-migration-%s-%s", account, region)
	return filepath.Join(abs, dir), nil
}

// ErrUnsafeCwd is returned by Resolve when the cwd is on the deny-list.
var ErrUnsafeCwd = errors.New("refusing to operate from this directory (try `mkdir -p ~/migrations && cd ~/migrations && migration-assistant`)")

// guardCwd enforces UX.md §0.4 guard 1.
func guardCwd(abs string) error {
	if abs == "/" {
		return fmt.Errorf("%w: cwd is filesystem root", ErrUnsafeCwd)
	}
	home, _ := os.UserHomeDir()
	if home != "" && abs == home {
		return fmt.Errorf("%w: cwd is exact $HOME (use a subdirectory)", ErrUnsafeCwd)
	}
	for _, forbidden := range []string{
		"/tmp", "/var", "/usr", "/opt", "/etc",
	} {
		if abs == forbidden || strings.HasPrefix(abs, forbidden+string(filepath.Separator)) {
			return fmt.Errorf("%w: cwd is under %s", ErrUnsafeCwd, forbidden)
		}
	}
	// Writable check — names the absolute path so users know which dir
	// to chmod or move out of.
	probe := filepath.Join(abs, ".ma-write-probe")
	f, err := os.Create(probe)
	if err != nil {
		return fmt.Errorf("%w: %s is not writable (%v)", ErrUnsafeCwd, abs, err)
	}
	_ = f.Close()
	_ = os.Remove(probe)
	return nil
}

// EnsureLayout creates the named subdir + transcripts/reports/notes if missing.
//
// Idempotent: never overwrites existing files; never deletes anything.
func EnsureLayout(workdir string) error {
	if err := os.MkdirAll(workdir, 0o755); err != nil {
		return fmt.Errorf("mkdir %s: %w", workdir, err)
	}
	for _, sub := range []string{"transcripts", "reports", "notes"} {
		if err := os.MkdirAll(filepath.Join(workdir, sub), 0o755); err != nil {
			return fmt.Errorf("mkdir %s: %w", sub, err)
		}
	}
	return nil
}

// LoadState reads the marker file. Returns nil State + nil error if absent.
func LoadState(workdir string) (*State, error) {
	p := filepath.Join(workdir, stateFile)
	b, err := os.ReadFile(p)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		return nil, fmt.Errorf("read %s: %w", p, err)
	}
	var st State
	if err := json.Unmarshal(b, &st); err != nil {
		return nil, fmt.Errorf("parse %s: %w", p, err)
	}
	return &st, nil
}

// SaveState atomically writes the marker file.
func SaveState(workdir string, st State) error {
	if st.SchemaVersion == 0 {
		st.SchemaVersion = schemaVer
	}
	st.UpdatedAt = time.Now().UTC().Truncate(time.Second)
	if err := EnsureLayout(workdir); err != nil {
		return err
	}
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	p := filepath.Join(workdir, stateFile)
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

// Guard returns the user-actionable verdict when an existing workdir
// disagrees with the current account/region/version (UX.md §0.4 guard 2).
type Guard int

const (
	GuardOK             Guard = iota // same account+region+version
	GuardOverwrite                   // different account/region/version — prompt user
	GuardCorrupt                     // marker exists but unparseable — safe-mode
	GuardFresh                       // no marker — first install
)

// Inspect resolves the guard verdict for the workdir vs. expected (acct, region, ver).
func Inspect(workdir, account, region, maVer string) (Guard, *State, error) {
	st, err := LoadState(workdir)
	if err != nil {
		return GuardCorrupt, nil, err
	}
	if st == nil {
		return GuardFresh, nil, nil
	}
	if st.AccountID == account && st.Region == region && st.MAVersion == maVer {
		return GuardOK, st, nil
	}
	return GuardOverwrite, st, nil
}
