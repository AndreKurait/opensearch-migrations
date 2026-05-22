package wizard_test

import (
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
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

func TestWizardStageEditUpdatesStackName(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// identity → region → stage.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	// Backspace four times to clear "dev" then type "prod".
	for i := 0; i < 5; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyBackspace})
	}
	for _, r := range "prod" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, "prod", m.CurrentState().Stage)
	require.Equal(t, "MA-Prod", m.CurrentState().StackName,
		"stage update must propagate into StackName so each stage gets its own CFN stack")
}

func TestWizardEKSAccessRevisitReplacesNotAppends(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// Walk to EKS access step (6 enters with default scope).
	for i := 0; i < 6; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	}
	// First commit: arn1.
	for _, r := range "arn1" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, []string{"arn1"}, m.CurrentState().AdditionalAccessARNs)

	// Back, edit value to arn2, re-commit.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	for i := 0; i < 4; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyBackspace})
	}
	for _, r := range "arn2" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.Equal(t, []string{"arn2"}, m.CurrentState().AdditionalAccessARNs,
		"revisiting EKS access must replace the slice, not append")
}

func TestWizardSaveExitOnPickerStepEmitsMessage(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// On stepIdentity ([s] not on textinput), pressing [s] emits SaveExitMsg.
	_, cmd := m.Update(tea.KeyPressMsg{Code: 's', Text: "s"})
	require.NotNil(t, cmd)
	out := cmd()
	se, ok := out.(wizard.SaveExitMsg)
	require.True(t, ok, "expected SaveExitMsg, got %T", out)
	require.Equal(t, "us-east-1", se.State.Region)
}

func TestWizardSaveExitDoesNotFireOnTextInputStep(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// Walk to stepRegion (textinput-focused).
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // identity → region
	// Press 's' — should reach the textinput, NOT emit SaveExitMsg.
	_, cmd := m.Update(tea.KeyPressMsg{Code: 's', Text: "s"})
	if cmd != nil {
		_, isSave := cmd().(wizard.SaveExitMsg)
		require.False(t, isSave, "[s] on stepRegion textinput must not save-exit; would break typing 'us-east-1'")
	}
}

func TestWizardLoadStateRestoresAllFields(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	st := wizard.State{
		Region: "eu-west-1", Stage: "prod",
		Scope:  "import-vpc",
		VPCID:  "vpc-deadbeef",
		Source: "build",
		TLSMode: "pca-create",
		AdditionalAccessARNs: []string{"arn:aws:iam::1:role/X"},
	}
	m.LoadState(st)
	got := m.CurrentState()
	require.Equal(t, "eu-west-1", got.Region)
	require.Equal(t, "prod", got.Stage)
	require.Equal(t, "import-vpc", got.Scope)
	require.Equal(t, "vpc-deadbeef", got.VPCID)
	require.Equal(t, "build", got.Source)
	require.Equal(t, "pca-create", got.TLSMode)
}

func TestWizardSubnetFilterNarrowsList(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m.SetSubnets([]feature.Subnet{
		{ID: "sub-public-1", AZ: "us-east-1a", CIDR: "10.0.1.0/24", RouteLabel: feature.RoutePublic},
		{ID: "sub-private-1", AZ: "us-east-1b", CIDR: "10.0.2.0/24", RouteLabel: feature.RouteIsolated},
		{ID: "sub-public-2", AZ: "us-east-1c", CIDR: "10.0.3.0/24", RouteLabel: feature.RoutePublic},
	})

	// Walk to subnets step.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // identity
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // region
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // stage
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})  // scope: import-vpc
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit scope
	for _, r := range "vpc-x" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit VPC → subnets

	// Press / and type 'public' — the list should narrow.
	m, _ = m.Update(tea.KeyPressMsg{Code: '/', Text: "/"})
	for _, r := range "public" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	out := m.View()
	require.Contains(t, out, "sub-public-1")
	require.Contains(t, out, "sub-public-2")
	require.NotContains(t, out, "sub-private-1", "filter should hide private subnet")

	// Esc clears the filter.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEscape})
	out = m.View()
	require.Contains(t, out, "sub-private-1", "esc should clear the filter and restore private subnets")
}

func TestWizardVPCFilterMatchesByName(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m.SetVPCs([]feature.VPC{
		{ID: "vpc-1", Name: "production", CIDR: "10.0.0.0/16"},
		{ID: "vpc-2", Name: "dev-cluster", CIDR: "10.1.0.0/16"},
	})

	// Walk to VPC step (import-vpc scope).
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown}) // scope: import-vpc
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})

	m, _ = m.Update(tea.KeyPressMsg{Code: '/', Text: "/"})
	for _, r := range "prod" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	out := m.View()
	require.Contains(t, out, "vpc-1")
	require.NotContains(t, out, "vpc-2", "filter 'prod' must hide dev-cluster")
}

func TestWizardFilterBackspaceShrinksFilter(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m.SetVPCs([]feature.VPC{
		{ID: "vpc-1", Name: "alpha"},
		{ID: "vpc-2", Name: "beta"},
	})
	// Walk to VPC step.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})  // import-vpc
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit scope

	m, _ = m.Update(tea.KeyPressMsg{Code: '/', Text: "/"})
	for _, r := range "alpha" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	require.Contains(t, m.View(), "vpc-1")
	require.NotContains(t, m.View(), "vpc-2")

	// Backspace four times → filter='a' → both match.
	for i := 0; i < 4; i++ {
		m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyBackspace})
	}
	out := m.View()
	require.Contains(t, out, "vpc-1")
	require.Contains(t, out, "vpc-2", "shrinking filter to 'a' must restore beta")
}

func TestWizardFilterEnterExitsEditMode(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	m.SetVPCs([]feature.VPC{{ID: "vpc-1", Name: "alpha"}})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})

	m, _ = m.Update(tea.KeyPressMsg{Code: '/', Text: "/"})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // exit filter mode
	// Now [enter] should commit the step (advance past VPC).
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
	require.NotContains(t, m.View(), "Filter '")
}

func TestWizardSubnetSpaceTogglesViaNamedKey(t *testing.T) {
	t.Parallel()
	m := makeWizard(t)
	// Inject 3 subnets so we can deselect one and still pass the
	// "at least 2 subnets required" validator. SetSubnets pre-selects
	// the first non-isolated subnet in each AZ until 2 are picked, so
	// only sub-1 and sub-2 start checked.
	m.SetSubnets([]feature.Subnet{
		{ID: "sub-1", AZ: "us-east-1a", CIDR: "10.0.1.0/24", RouteLabel: feature.RouteNAT},
		{ID: "sub-2", AZ: "us-east-1b", CIDR: "10.0.2.0/24", RouteLabel: feature.RouteNAT},
		{ID: "sub-3", AZ: "us-east-1c", CIDR: "10.0.3.0/24", RouteLabel: feature.RouteNAT},
	})

	// Walk to subnets step in import-vpc scope.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // identity
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // region
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // stage
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})  // scope: import-vpc
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit scope
	for _, r := range "vpc-1" {
		m, _ = m.Update(tea.KeyPressMsg{Code: r, Text: string(r)})
	}
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit VPC → subnets

	// Now on subnets. cursor=0 (sub-1, pre-selected). Down to cursor=2,
	// space to add sub-3 — proves toggle is wired through key.Matches.
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeySpace, Text: " "})
	m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter}) // commit
	require.ElementsMatch(t, []string{"sub-1", "sub-2", "sub-3"}, m.CurrentState().SubnetIDs,
		"space-toggle must add the cursor's subnet — bug class: literal ' ' vs named 'space'")
}
