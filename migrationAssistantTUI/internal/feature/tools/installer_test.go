package tools

import (
	"context"
	"strings"
	"testing"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

// TestInstaller_Supports asserts the installer advertises only the
// tools the welcome page renders as "installable" (Tool.Installable
// == true in All). Adding a new installable tool must update both
// places; this test catches drift.
func TestInstaller_Supports(t *testing.T) {
	got := NewInstaller().Supports()
	want := map[string]bool{"helm": false, "kiro-cli": false}
	for _, n := range got {
		if _, ok := want[n]; !ok {
			t.Fatalf("Installer.Supports returned unknown tool %q (want subset of %v)", n, keys(want))
		}
		want[n] = true
	}
	for n, seen := range want {
		if !seen {
			t.Errorf("Installer.Supports missing %q", n)
		}
	}
}

// TestInstaller_UnsupportedTool asserts Install returns a typed error
// for tools outside Supports() and does NOT spawn any subprocess.
// Validates the contract documented on feature.ToolInstaller.
func TestInstaller_UnsupportedTool(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	events := make(chan feature.InstallEvent, 4)
	err := NewInstaller().Install(ctx, "kubectl", events)
	close(events)
	if err == nil {
		t.Fatalf("Install(kubectl) should return error, got nil")
	}
	if !strings.Contains(err.Error(), "unsupported") {
		t.Errorf("Install(kubectl) error = %q, want contains 'unsupported'", err.Error())
	}
	for ev := range events {
		t.Errorf("expected no events for unsupported tool, got %+v", ev)
	}
}

// TestInstaller_NilEventsChannel asserts the Install adapter tolerates
// a nil events channel (silent install). Used by callers that don't
// need progress streaming.
func TestInstaller_NilEventsChannel(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // pre-cancelled — InstallHelm should bail fast on ctx
	// Just verify the call doesn't panic on nil channel; we don't
	// care if InstallHelm itself errors on a cancelled ctx.
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("Install with nil events panicked: %v", r)
		}
	}()
	_ = NewInstaller().Install(ctx, "helm", nil)
}

func keys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
