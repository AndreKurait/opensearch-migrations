package wizard_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/testutil"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

// makeWizard is a helper used by the deeper-coverage tests below.
func makeWizard(t *testing.T) *wizard.Model {
	t.Helper()
	ws := testutil.NewFakeWS(t)
	return wizard.New(common.New(ws))
}

// advanceTo presses enter (and optionally other keys) until the active
// step is `target`. Returns when reached or fails the test if the
// wizard finishes first.
//
// Step layout (UX.md §8.1):
//
//	0 identity → 1 region → 2 stage → 3 scope → 4 vpc → 5 subnets →
//	6 endpoints → 7 source → 8 tls → 9 eks → 10 advanced
func TestWizardImportVPCRequiresVPCID(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// identity → region → stage → scope.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	// Pick "Import VPC" (down once → idx 1).
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	// Confirm scope; advance to VPC step.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, "import-vpc", m.CurrentState().Scope)

	// Submit empty VPC → validation error, no advance.
	m, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Nil(t, cmd, "validation failure must not emit cmd")
	require.Contains(t, m.View(), "VPC ID required when importing")
}

func TestWizardImportVPCSubnetsRequiresAtLeastTwo(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // identity
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // region
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // stage
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})  // scope: Import VPC
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit scope → VPC step
	for _, r := range "vpc-0abc" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	// commit VPC → advance to Subnets step (VPCID is committed here).
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, "vpc-0abc", m.CurrentState().VPCID)

	// Submit without entering subnets → validation error.
	m, cmd := m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Nil(t, cmd)
	require.Contains(t, m.View(), "at least 2 subnet IDs required")
}

func TestWizardSourcePickerCyclesValues(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// With default scope=create-vpc, VPC/Subnets/Endpoints are skipped.
	// Enters: identity → region → stage → scope → source. That's 4 enters.
	for i := 0; i < 4; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	require.Equal(t, "published", m.CurrentState().Source)

	// Down twice → ecr-mirror.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, "ecr-mirror", m.CurrentState().Source)
}

func TestWizardTLSPickerSelectsPCACreate(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// Enters: identity → region → stage → scope → source → tls. 5 enters.
	for i := 0; i < 5; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	// Default cursor 1 (self-signed) → press down twice → pca-create.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, "pca-create", m.CurrentState().TLSMode)
}

func TestWizardEKSAccessAcceptsArn(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	for i := 0; i < 6; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	for _, r := range "arn:aws:iam::1:role/X" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, []string{"arn:aws:iam::1:role/X"}, m.CurrentState().AdditionalAccessARNs)
}

func TestWizardEscMovesBack(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // → region
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // → stage
	// Esc returns to region.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	require.True(t, strings.Contains(m.View(), "Region: "), "should have backed to Region step")
}

func TestWizardViewListsAllSteps(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // identity
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // region
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // stage
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})  // scope: Import VPC
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit
	out := m.View()
	for _, want := range []string{
		"AWS identity", "Region", "Stage name", "Deployment scope",
		"VPC", "Subnets",
		"Source", "TLS mode", "Additional EKS access", "Advanced",
	} {
		require.True(t, strings.Contains(out, want), "view missing step %q", want)
	}
}

func TestWizardSkipsImportVPCStepsForCreateVPC(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	out := m.View()
	// VPC, Subnets, and VPC endpoints (steps 5, 6, 7) should all be
	// hidden when scope=create-vpc (the default).
	require.NotContains(t, out, "Subnets", "create-vpc default should hide the Subnets step")
	require.NotContains(t, out, "VPC endpoints", "create-vpc default should hide the VPC endpoints step")
	// Step 5 (VPC) row is hidden — verify by checking that step rows jump
	// from "4 " (Deployment scope) to "8 " (Source).
	require.Contains(t, out, "4   ", "Deployment scope row should still be present (number 4)")
	require.Contains(t, out, "8   ", "Source row should be present (number 8) — 5/6/7 skipped")
	require.NotContains(t, out, "5   ", "VPC row (number 5) should be skipped")
}

func TestWizardSetIdentityShowsOnFirstStep(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m.SetIdentity("arn:aws:iam::123:user/Admin")
	require.Contains(t, m.View(), "user/Admin")
}
