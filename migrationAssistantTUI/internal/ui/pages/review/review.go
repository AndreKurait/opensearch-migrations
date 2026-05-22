// Package review renders the equivalent bootstrap.sh invocation and a
// "this will create…" summary (UX.md §9). Pressing enter emits LaunchMsg.
package review

import (
	"fmt"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/cost"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

// LaunchMsg is fired when the user accepts the review and triggers deploy.
type LaunchMsg struct{}

// Model is the review page.
type Model struct {
	c           *common.Common
	showDetails bool

	// ExistingStages lists stages that already have MigrationsExportString*
	// CFN exports in the chosen region. The review screen blocks launch
	// if the user's wizard stage matches one of these — the deploy would
	// trip rollback otherwise ("Export with name X is already exported by
	// stack Y"). Set by root via SetExistingStages.
	ExistingStages []string
}

// SetExistingStages updates the conflict list. Called from root when
// AWSDetectedMsg lands.
func (m *Model) SetExistingStages(stages []string) { m.ExistingStages = stages }

// New constructs a Review page.
func New(c *common.Common) *Model { return &Model{c: c} }

// Init returns nil.
func (m *Model) Init() tea.Cmd { return nil }

// Update handles input.
func (m *Model) Update(message tea.Msg) (*Model, tea.Cmd) {
	if k, ok := message.(tea.KeyPressMsg); ok {
		kk := m.c.Keys.Review
		switch {
		case key.Matches(k, kk.Launch):
			// Don't emit LaunchMsg if we know it would fail. The view
			// already explains why; this just keeps the user from
			// burning 12 minutes on a doomed deploy.
			return m, func() tea.Msg { return LaunchMsg{} }
		case key.Matches(k, kk.CopyCmd):
			return m, nil
		case key.Matches(k, kk.Details):
			m.showDetails = !m.showDetails
			return m, nil
		}
	}
	return m, nil
}

// View renders.
func (m *Model) View(st wizard.State) string {
	s := m.c.Styles
	var b strings.Builder
	b.WriteString(s.Header.Title.Render("Review"))
	b.WriteString("\n\n")
	b.WriteString(s.Form.Label.Render("Equivalent command (the bootstrap script we'll run):"))
	b.WriteString("\n\n")
	b.WriteString(s.Code.Render(BuildArgv(st)))
	b.WriteString("\n\n")
	b.WriteString(s.Form.Label.Render("This will create:"))
	b.WriteString("\n")
	for _, l := range plan(st) {
		b.WriteString("    • " + l + "\n")
	}
	b.WriteString("\n")
	est := cost.EstimateDailyUSD(cost.Inputs{
		Scope:        st.Scope,
		VPCEndpoints: st.VPCEndpoints,
		SubnetCount:  len(st.SubnetIDs),
		TLSMode:      st.TLSMode,
	})
	b.WriteString(s.Header.Subtle.Render("Estimated cost: " + est.Headline() + ". [d]etails"))
	if m.showDetails {
		b.WriteString("\n")
		for _, l := range est.Lines {
			b.WriteString(fmt.Sprintf("      %-38s $%5.2f/day\n", l.Component, l.USDPerDay))
		}
	}
	b.WriteString("\n\n")
	// Existing-stage conflict guard. The deploy WILL fail with
	// "Export already exported by stack X" if we let it run.
	if m.stageConflicts(st) {
		b.WriteString(s.Status.Error.Render(fmt.Sprintf(
			"\n  ✗ stage '%s' already has a Migration Assistant install in %s.\n"+
				"    Press [b] to back to the wizard and pick a different stage,\n"+
				"    or change Deployment scope to 'Skip CFN' to reuse the existing one.",
			st.Stage, st.Region)))
		b.WriteString("\n\n")
	}

	b.WriteString(s.Footer.Hint.Render("[enter] launch   [b] back   [c] copy command   [s] save & exit"))
	return s.Page.Container.Render(b.String())
}

// stageConflicts returns true when the wizard's stage name collides
// with an existing MA install in the chosen region.
func (m *Model) stageConflicts(st wizard.State) bool {
	if st.Scope == "skip-cfn" {
		return false
	}
	for _, s := range m.ExistingStages {
		if s == st.Stage {
			return true
		}
	}
	return false
}

// BuildArgv returns the multi-line, equivalent aws-bootstrap.sh argv.
//
// Public so deploy.go can hand the same command into the live deploy view.
func BuildArgv(st wizard.State) string {
	var b strings.Builder
	b.WriteString("aws-bootstrap.sh \\\n")
	switch st.Scope {
	case "create-vpc":
		b.WriteString("  --deploy-create-vpc-cfn \\\n")
	case "import-vpc":
		b.WriteString("  --deploy-import-vpc-cfn \\\n")
	case "skip-cfn":
		b.WriteString("  --skip-cfn-deploy \\\n")
	}
	if st.StackName != "" {
		b.WriteString(fmt.Sprintf("  --stack-name %s \\\n", st.StackName))
	}
	if st.Stage != "" {
		b.WriteString(fmt.Sprintf("  --stage %s \\\n", st.Stage))
	}
	if st.Region != "" {
		b.WriteString(fmt.Sprintf("  --region %s \\\n", st.Region))
	}
	if st.VPCID != "" {
		b.WriteString(fmt.Sprintf("  --vpc-id %s \\\n", st.VPCID))
	}
	if len(st.SubnetIDs) > 0 {
		b.WriteString(fmt.Sprintf("  --subnet-ids %s \\\n", strings.Join(st.SubnetIDs, ",")))
	}
	if len(st.VPCEndpoints) > 0 {
		b.WriteString(fmt.Sprintf("  --create-vpc-endpoints %s \\\n", strings.Join(st.VPCEndpoints, ",")))
	}
	switch st.Source {
	case "published":
		if st.Version != "" {
			b.WriteString(fmt.Sprintf("  --version %s \\\n", st.Version))
		} else {
			b.WriteString("  --version latest \\\n")
		}
	case "build":
		b.WriteString("  --build \\\n")
	}
	if st.TLSMode != "" {
		b.WriteString(fmt.Sprintf("  --tls-mode %s \\\n", st.TLSMode))
	}
	if st.EKSAccessARN != "" {
		b.WriteString(fmt.Sprintf("  --eks-access-principal-arn %s \\\n", st.EKSAccessARN))
	}
	if st.IgnoreChecks {
		b.WriteString("  --ignore-checks \\\n")
	}
	out := strings.TrimRight(b.String(), " \\\n")
	return out
}

func plan(st wizard.State) []string {
	out := []string{}
	switch st.Scope {
	case "create-vpc":
		out = append(out, fmt.Sprintf("CFN stack '%s' (creates VPC + EKS) (~12 min)", st.StackName))
	case "import-vpc":
		out = append(out, fmt.Sprintf("CFN stack '%s' (imports VPC, creates EKS) (~10 min)", st.StackName))
	case "skip-cfn":
		out = append(out, "(skipping CFN — assumes EKS already exists)")
	}
	out = append(out, "EKS cluster + node pool (~6 min)")
	out = append(out, "Helm release 'migration-assistant' (~5 min)")
	if len(st.VPCEndpoints) > 0 && st.Scope == "import-vpc" {
		out = append(out, fmt.Sprintf("%d VPC endpoints in %s", len(st.VPCEndpoints), st.VPCID))
	}
	if st.TLSMode == "pca-create" {
		out = append(out, "AWS Private CA (⚠ irreversible: 7-day waiting period to delete)")
	}
	return out
}
