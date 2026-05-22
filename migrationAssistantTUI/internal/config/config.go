// Package config holds runtime configuration loaded once at app start.
//
// Persistent on-disk config (theme, "always skip" version pins, etc.)
// lives at ~/.config/opensearch-migration-assistant/config.json — see
// UX.md §5.2.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// Config is the persistent user-config file. Defaults are zero-value safe.
//
// Secrets must NEVER live here — UX.md §3 mandates OS keychain.
type Config struct {
	// Theme is the user's theme preference (dark|light|hi-contrast|auto).
	Theme string `json:"theme,omitempty"`

	// SkippedTUIVersions records "always skip on this version" choices
	// from the launch-time update prompt (UX.md §0.5).
	SkippedTUIVersions []string `json:"skipped_tui_versions,omitempty"`

	// LastTUIVersionCheck is an RFC3339 timestamp; used to honor the
	// 24h cache (UX.md §0.5).
	LastTUIVersionCheck string `json:"last_tui_version_check,omitempty"`

	// LastAgentVersionCheck — 6h cache for §0.6.
	LastAgentVersionCheck string `json:"last_agent_version_check,omitempty"`
}

// Default returns a zero-value Config. Centralized so tests can use it.
func Default() Config { return Config{Theme: "dark"} }

// Path returns the canonical user-config path.
func Path() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("user config dir: %w", err)
	}
	return filepath.Join(dir, "opensearch-migration-assistant", "config.json"), nil
}

// Load reads the config file from disk. Missing file is not an error —
// returns Default() with the canonical path.
func Load() (Config, string, error) {
	p, err := Path()
	if err != nil {
		return Default(), "", err
	}
	b, err := os.ReadFile(p)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return Default(), p, nil
		}
		return Default(), p, fmt.Errorf("read %s: %w", p, err)
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return Default(), p, fmt.Errorf("parse %s: %w", p, err)
	}
	return c, p, nil
}

// Save writes c atomically (write-temp + rename) to the canonical path.
func Save(c Config) error {
	p, err := Path()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return fmt.Errorf("mkdir %s: %w", filepath.Dir(p), err)
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}
