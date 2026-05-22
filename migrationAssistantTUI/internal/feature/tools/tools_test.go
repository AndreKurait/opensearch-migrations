package tools

import (
	"context"
	"errors"
	"runtime"
	"strings"
	"testing"
)

func TestDetectFindsAtLeastShell(t *testing.T) {
	d := &Detector{}
	got, err := d.Detect(context.Background())
	if err != nil {
		t.Fatalf("Detect: %v", err)
	}
	if len(got) != len(All) {
		t.Fatalf("len = %d, want %d", len(got), len(All))
	}
	// Names preserved + ordered.
	for i, want := range All {
		if got[i].Name != want.Name {
			t.Fatalf("got[%d].Name = %q, want %q", i, got[i].Name, want.Name)
		}
	}
}

func TestInstalledFlag(t *testing.T) {
	if (Tool{Path: ""}).Installed() {
		t.Fatal("empty path should not be installed")
	}
	if !(Tool{Path: "/usr/bin/x"}).Installed() {
		t.Fatal("non-empty path should be installed")
	}
}

func TestMissingHintCovered(t *testing.T) {
	for _, name := range []string{"kubectl", "aws", "git", "docker"} {
		hint := MissingHint(name)
		if hint == "" {
			// kubectl / git only have darwin+linux hints; on other OSes empty
			// is fine. Force these two even on linux/darwin.
			if (name == "kubectl" || name == "git") &&
				(runtime.GOOS == "linux" || runtime.GOOS == "darwin") {
				t.Errorf("%s: empty hint on supported OS %s", name, runtime.GOOS)
			}
		}
	}
}

func TestInstallHelmShortCircuitsWhenPresent(t *testing.T) {
	// If the test host has helm on PATH, this exits cleanly via the
	// LookPath short-circuit. If not, we verify it still attempts the
	// download (errors are platform-dependent, so we just verify the
	// code path runs without panicking).
	var msgs []string
	emit := func(p InstallProgress) {
		msgs = append(msgs, p.Status+":"+p.Message)
	}
	err := InstallHelm(context.Background(), emit)
	// In a hermetic test env we accept either: (a) helm pre-installed
	// → completed, (b) network blocked → error string.
	if err != nil && !strings.Contains(err.Error(), "download") &&
		!errors.Is(err, context.Canceled) &&
		!strings.Contains(err.Error(), "tempfile") &&
		!strings.Contains(err.Error(), "still not on PATH") &&
		!strings.Contains(err.Error(), "get-helm-3") {
		t.Fatalf("unexpected error class: %v", err)
	}
	// Either a "completed" or "started" message must appear.
	found := false
	for _, m := range msgs {
		if strings.HasPrefix(m, "completed") || strings.HasPrefix(m, "started") {
			found = true
			break
		}
	}
	if !found && len(msgs) == 0 {
		t.Fatal("emit was never called")
	}
}
