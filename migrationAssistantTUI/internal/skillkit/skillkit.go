// Package skillkit produces per-agent adapters from the agent-agnostic
// skill bundle (UX.md §0.2, §12).
//
// v1 simplification (locked **r9**): the agent-agnostic bundle is derived
// at install time from `kiro-assistant.tar.gz`, not pre-built upstream.
// For Kiro we skip the agnostic step and use the upstream tarball
// directly (pixel-perfect with `bootstrap-kiro-agent.sh`).
package skillkit

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Agent identifies which adapter we're writing.
type Agent string

const (
	AgentKiro       Agent = "kiro"
	AgentClaudeCode Agent = "claude-code"
)

// Install extracts the bundle to `<workdir>/opensearch-migration-skills/`
// and writes the per-agent adapter under `.kiro/` or `.claude/`.
//
// `bundleTar` is a path to the tarball already fetched by ArtifactSource
// (typically `kiro-assistant.tar.gz` for v1 — UX.md §0.7).
func Install(workdir string, agent Agent, bundleTar string) error {
	if agent != AgentKiro && agent != AgentClaudeCode {
		return fmt.Errorf("unsupported agent %q (v1: kiro|claude-code)", agent)
	}
	if _, err := os.Stat(bundleTar); err != nil {
		return fmt.Errorf("bundle tar %s: %w", bundleTar, err)
	}

	bundleDir := filepath.Join(workdir, "opensearch-migration-skills")
	if err := os.MkdirAll(bundleDir, 0o755); err != nil {
		return err
	}
	// Untar into bundleDir.
	if err := extractTarGz(bundleTar, bundleDir); err != nil {
		return fmt.Errorf("extract bundle: %w", err)
	}

	switch agent {
	case AgentKiro:
		// Pixel-perfect: copy bundle's contents into ./.kiro/.
		// Upstream `kiro-assistant.tar.gz` already ships .kiro/ at root
		// of the tarball, so the bundleDir IS the .kiro layout. We just
		// rename / symlink.
		dst := filepath.Join(workdir, ".kiro")
		// If the bundle has a top-level .kiro, move it. Otherwise copy bundleDir.
		src := filepath.Join(bundleDir, ".kiro")
		if _, err := os.Stat(src); err == nil {
			if err := os.RemoveAll(dst); err != nil && !os.IsNotExist(err) {
				return err
			}
			if err := os.Rename(src, dst); err != nil {
				return fmt.Errorf("rename .kiro: %w", err)
			}
		} else if err := copyTree(bundleDir, dst); err != nil {
			return err
		}
	case AgentClaudeCode:
		// Generate Claude Code skill kit at ./.claude/skills/opensearch-migration/.
		dst := filepath.Join(workdir, ".claude", "skills", "opensearch-migration")
		if err := os.MkdirAll(dst, 0o755); err != nil {
			return err
		}
		// Iterate the bundle's skills/ directory and translate each .md.
		skills := filepath.Join(bundleDir, "skills")
		if _, err := os.Stat(skills); err != nil {
			// Bundle didn't have skills/ — fall back to whole-bundle copy.
			skills = bundleDir
		}
		entries, err := os.ReadDir(skills)
		if err != nil {
			return err
		}
		var index []string
		for _, e := range entries {
			if e.IsDir() {
				continue
			}
			name := e.Name()
			if !strings.HasSuffix(name, ".md") {
				continue
			}
			src := filepath.Join(skills, name)
			out := filepath.Join(dst, name)
			if err := copyFile(src, out); err != nil {
				return err
			}
			index = append(index, "- "+name)
		}
		// Write a SKILL.md index.
		idx := "# OpenSearch Migration Skills (Claude Code adapter)\n\n" +
			"Read `start.md` first, then follow the migration phase your skill kit refers to.\n\n" +
			"Files:\n" + strings.Join(index, "\n") + "\n"
		if err := os.WriteFile(filepath.Join(dst, "SKILL.md"), []byte(idx), 0o644); err != nil {
			return err
		}
	}
	return nil
}

func extractTarGz(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		// Reject path traversal; UX.md is implicit but we don't trust archives.
		clean := filepath.Clean(h.Name)
		if strings.HasPrefix(clean, "..") || filepath.IsAbs(clean) {
			return fmt.Errorf("unsafe tar entry: %q", h.Name)
		}
		out := filepath.Join(dst, clean)
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(out, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
				return err
			}
			fw, err := os.OpenFile(out, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
			if err != nil {
				return err
			}
			if _, err := io.Copy(fw, tr); err != nil {
				_ = fw.Close()
				return err
			}
			if err := fw.Close(); err != nil {
				return err
			}
		}
	}
	return nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		return err
	}
	return out.Close()
}

func copyTree(src, dst string) error {
	return filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, p)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		return copyFile(p, target)
	})
}
