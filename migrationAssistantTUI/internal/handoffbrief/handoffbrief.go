// Package handoffbrief writes ./HANDOFF.md per UX.md §12.4 — YAML
// frontmatter (machine-parseable) plus a markdown body (the user's stated
// goal). The `@start` skill instructs the agent to read this file first.
//
// Credentials are NEVER inlined; only a keychain item ID is referenced
// (locked **X**).
package handoffbrief

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// Brief is the schema we serialize. Embedded in HANDOFF.md frontmatter.
type Brief struct {
	MAVersion     string `yaml:"ma_version"`
	AWSAccount    string `yaml:"aws_account"`
	Region        string `yaml:"region"`
	EKSCluster    string `yaml:"eks_cluster"`
	Namespace     string `yaml:"namespace"`
	Stage         string `yaml:"stage"`
	Source        Source `yaml:"source"`
	Target        Target `yaml:"target"`
	ConsoleExec   string `yaml:"console_exec,omitempty"`
	WrittenAt     string `yaml:"written_at"`
	SchemaVersion int    `yaml:"schema_version"`
}

// Source is the source-cluster summary.
type Source struct {
	Endpoint       string `yaml:"endpoint,omitempty"`
	Engine         string `yaml:"engine,omitempty"`
	EngineVersion  string `yaml:"engine_version,omitempty"`
	AuthMethod     string `yaml:"auth_method,omitempty"`
	AuthKeychainID string `yaml:"auth_keychain_id,omitempty"`
	ApproxSize     string `yaml:"approx_size,omitempty"`
}

// Target is the destination summary.
type Target struct {
	Type     string `yaml:"type,omitempty"`     // "new-opensearch-domain" | "existing" | "self-managed"
	Endpoint string `yaml:"endpoint,omitempty"` // "<pending discovery>" until known
}

// Write serializes the brief and the user's free-text goal to
// `<workdir>/HANDOFF.md`. Atomic (write-temp + rename).
func Write(workdir string, b Brief, goal string) error {
	if b.WrittenAt == "" {
		b.WrittenAt = time.Now().UTC().Format(time.RFC3339)
	}
	if b.SchemaVersion == 0 {
		b.SchemaVersion = 1
	}
	frontmatter, err := yaml.Marshal(b)
	if err != nil {
		return fmt.Errorf("marshal brief: %w", err)
	}
	var sb strings.Builder
	sb.WriteString("---\n")
	sb.Write(frontmatter)
	sb.WriteString("---\n\n")
	sb.WriteString("# Migration goal\n\n")
	if strings.TrimSpace(goal) == "" {
		sb.WriteString("(user skipped intent capture; ask before proceeding)\n")
	} else {
		sb.WriteString(strings.TrimSpace(goal))
		sb.WriteString("\n")
	}
	if err := os.MkdirAll(workdir, 0o755); err != nil {
		return err
	}
	p := filepath.Join(workdir, "HANDOFF.md")
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, []byte(sb.String()), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}
