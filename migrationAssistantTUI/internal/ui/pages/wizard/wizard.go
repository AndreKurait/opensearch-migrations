// Package wizard implements the setup wizard (UX.md §8).
//
// Vertical step list. Each step is a small Update/View func that owns
// its own focus + cursor; the wizard owns the index pointer and the
// shared State that all steps populate.
package wizard

import (
	"fmt"
	"sort"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/textinput"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
)

// State is what the wizard accumulates and ships to Review/Deploy.
//
// Mirrors the resolved bootstrap.sh argv (UX.md §9). Pages further down
// the flow only read this struct.
type State struct {
	Identity string // sts:GetCallerIdentity → user/role ARN, displayed as principal name
	Region   string
	Stage    string

	// Deployment scope: "create-vpc" | "import-vpc" | "skip-cfn"
	Scope string

	VPCID        string
	SubnetIDs    []string
	VPCEndpoints []string // "s3", "ecr", "ecrDocker"

	Source  string // "published" | "build" | "ecr-mirror"
	Version string
	TLSMode string // "none" | "self-signed" | "pca-import" | "pca-create"

	EKSAccessARN string // legacy single principal (kept for review.BuildArgv compat)
	AdditionalAccessARNs []string // extra principals beyond the caller
	Namespace    string
	StackName    string

	// Advanced overrides — flat map per UX.md §0.1.
	Advanced map[string]string

	IgnoreChecks bool
}

// CompletedMsg is fired when the wizard is finalized.
type CompletedMsg struct{ State State }

// VPCSelectedMsg is emitted by the wizard when a VPC is committed.
// Root listens for it and triggers subnet detection.
type VPCSelectedMsg struct{ VPCID, Region string }

// Step IDs — kept as named constants so renumbering is painless.
const (
	stepIdentity = iota
	stepRegion
	stepStage
	stepScope
	stepVPC
	stepSubnets
	stepEndpoints
	stepSource
	stepTLS
	stepEKSAccess
	stepAdvanced

	stepCount
)

// stepDefs declares the linear order of wizard steps. Indices MUST
// match the constants above.
var stepDefs = []struct {
	label string
	help  string
}{
	{"AWS identity", "current role/user (from sts:GetCallerIdentity)"},
	{"Region", "AWS region to deploy into"},
	{"Stage name", "default 'dev'"},
	{"Deployment scope", "Create VPC / Import VPC / Skip CFN"},
	{"VPC", "auto-detect or paste an ID"},
	{"Subnets", "multi-select w/ NAT/IGW/isolated"},
	{"VPC endpoints", "auto-detected; only missing ones proposed"},
	{"Source", "Published version / Build from source"},
	{"TLS mode", "none / self-signed / PCA / new PCA"},
	{"Additional EKS access", "current principal is auto-granted; add more here"},
	{"Advanced", "namespace, image tag, node pools…"},
}

// Model is the wizard page state.
type Model struct {
	c   *common.Common
	cur int
	showInfo bool

	// Step inputs
	stage     textinput.Model
	region    textinput.Model
	vpcID     textinput.Model
	subnetIDs textinput.Model
	stackName textinput.Model
	namespace textinput.Model
	eksARN    textinput.Model

	scopeIdx  int
	sourceIdx int
	tlsIdx    int

	// VPC picker — only used when scope=="import-vpc". cursor=-1 means
	// the user is in manual-entry mode (typing into vpcID).
	vpcCursor int

	// Subnet picker — multi-select (true == picked).
	subnetSelected map[string]bool
	subnetCursor   int

	state State

	// Cached AWS data (populated via SetVPCs/SetSubnets from root).
	vpcs      []feature.VPC
	subnets   []feature.Subnet
	endpoints []feature.VPCEndpoint

	err string
}

// New constructs a wizard with sane defaults from UX.md §6.2.
func New(c *common.Common) *Model {
	m := &Model{c: c, vpcCursor: -1}
	mk := func(initial string, width int) textinput.Model {
		t := textinput.New()
		t.SetValue(initial)
		t.SetWidth(width)
		return t
	}
	m.stage = mk("dev", 30)
	m.region = mk("us-east-1", 30)
	m.vpcID = mk("", 30)
	m.subnetIDs = mk("", 50)
	m.stackName = mk("MA-Dev", 30)
	m.namespace = mk("migration-assistant", 30)
	m.eksARN = mk("", 60)
	m.scopeIdx = 0
	m.sourceIdx = 0
	m.tlsIdx = 1 // self-signed (script default)
	m.subnetSelected = map[string]bool{}

	// Pre-populate state with defaults so the user can ENTER through
	// every step without filling anything in (UX.md §0.1).
	m.state = State{
		Region:       "us-east-1",
		Stage:        "dev",
		Scope:        "create-vpc",
		Source:       "published",
		TLSMode:      "self-signed",
		Namespace:    "migration-assistant",
		StackName:    "MA-Dev",
		VPCEndpoints: []string{"s3", "ecr", "ecrDocker"},
		Advanced:     map[string]string{},
	}
	return m
}

// Init returns nil.
func (m *Model) Init() tea.Cmd { return nil }

// Update handles input.
func (m *Model) Update(message tea.Msg) (*Model, tea.Cmd) {
	switch v := message.(type) {
	case tea.KeyPressMsg:
		kk := m.c.Keys.Wizard
		switch {
		case key.Matches(v, kk.PrevStep):
			if m.cur > 0 {
				m.cur--
			}
			return m, nil
		case key.Matches(v, kk.Info):
			m.showInfo = !m.showInfo
			return m, nil
		case key.Matches(v, kk.NextStep):
			if err := m.commitStep(); err != nil {
				m.err = err.Error()
				return m, nil
			}
			m.err = ""
			// Side effects on a completed step.
			var sideCmd tea.Cmd
			if m.cur == stepVPC && m.state.VPCID != "" {
				selected := m.state.VPCID
				region := m.state.Region
				sideCmd = func() tea.Msg { return VPCSelectedMsg{VPCID: selected, Region: region} }
			}
			next := m.cur + 1
			// Skip VPC/Subnets/Endpoints when scope=="create-vpc" or
			// "skip-cfn" — those steps are import-vpc-only.
			for next < stepCount && m.skipStep(next) {
				next++
			}
			if next >= stepCount {
				return m, tea.Batch(sideCmd, m.complete())
			}
			m.cur = next
			return m, sideCmd
		}
		// Step-specific keys
		return m.dispatchStep(v)
	}
	return m, nil
}

// skipStep returns true when the given step should be hidden for the
// current state (e.g. VPC picker is meaningless for "create-vpc").
func (m *Model) skipStep(idx int) bool {
	switch idx {
	case stepVPC, stepSubnets:
		return m.state.Scope != "import-vpc"
	case stepEndpoints:
		// Only show endpoints if at least one selected subnet is isolated.
		if m.state.Scope != "import-vpc" {
			return true
		}
		for _, id := range m.state.SubnetIDs {
			for _, s := range m.subnets {
				if s.ID == id && s.RouteLabel == feature.RouteIsolated {
					return false
				}
			}
		}
		return true
	}
	return false
}

// dispatchStep routes a key to the focused step. Each step's text
// inputs are focused on entry; the previous step's input is blurred.
func (m *Model) dispatchStep(k tea.KeyPressMsg) (*Model, tea.Cmd) {
	m.focusCurrentStep()
	var cmd tea.Cmd
	switch m.cur {
	case stepIdentity:
		// No edits — informational.
	case stepRegion:
		m.region, cmd = m.region.Update(k)
	case stepStage:
		m.stage, cmd = m.stage.Update(k)
	case stepScope:
		switch k.String() {
		case "left", "up":
			if m.scopeIdx > 0 {
				m.scopeIdx--
			}
		case "right", "down":
			if m.scopeIdx < 2 {
				m.scopeIdx++
			}
		}
	case stepVPC:
		// If we have detected VPCs, up/down navigates the list; 'm' or
		// any character switches to manual entry.
		if len(m.vpcs) > 0 && m.vpcCursor >= 0 {
			switch k.String() {
			case "up":
				if m.vpcCursor > 0 {
					m.vpcCursor--
				}
				return m, nil
			case "down":
				if m.vpcCursor < len(m.vpcs)-1 {
					m.vpcCursor++
				}
				return m, nil
			case "m":
				m.vpcCursor = -1
				return m, nil
			}
		}
		m.vpcID, cmd = m.vpcID.Update(k)
	case stepSubnets:
		// If we have detected subnets, up/down navigates and space toggles.
		if len(m.subnets) > 0 {
			switch k.String() {
			case "up":
				if m.subnetCursor > 0 {
					m.subnetCursor--
				}
				return m, nil
			case "down":
				if m.subnetCursor < len(m.subnets)-1 {
					m.subnetCursor++
				}
				return m, nil
			case " ", "space":
				id := m.subnets[m.subnetCursor].ID
				m.subnetSelected[id] = !m.subnetSelected[id]
				return m, nil
			}
		}
		m.subnetIDs, cmd = m.subnetIDs.Update(k)
	case stepSource:
		switch k.String() {
		case "up":
			if m.sourceIdx > 0 {
				m.sourceIdx--
			}
		case "down":
			if m.sourceIdx < 2 {
				m.sourceIdx++
			}
		}
	case stepTLS:
		switch k.String() {
		case "up":
			if m.tlsIdx > 0 {
				m.tlsIdx--
			}
		case "down":
			if m.tlsIdx < 3 {
				m.tlsIdx++
			}
		}
	case stepEKSAccess:
		m.eksARN, cmd = m.eksARN.Update(k)
	case stepAdvanced:
		// (No edits in v1 — placeholder for the named overrides screen.)
	}
	return m, cmd
}

// focusCurrentStep ensures the textinput owned by m.cur has Focus()
// and all others are blurred. Called every dispatchStep so the input
// is ready to receive characters.
func (m *Model) focusCurrentStep() {
	// Blur all.
	m.region.Blur()
	m.stage.Blur()
	m.vpcID.Blur()
	m.subnetIDs.Blur()
	m.eksARN.Blur()
	m.stackName.Blur()
	m.namespace.Blur()
	// Focus the active step's input.
	switch m.cur {
	case stepRegion:
		m.region.Focus()
	case stepStage:
		m.stage.Focus()
	case stepVPC:
		m.vpcID.Focus()
	case stepSubnets:
		m.subnetIDs.Focus()
	case stepEKSAccess:
		m.eksARN.Focus()
	}
}

func (m *Model) commitStep() error {
	switch m.cur {
	case stepIdentity:
		// Informational; nothing to commit.
	case stepRegion:
		v := strings.TrimSpace(m.region.Value())
		if v == "" {
			return fmt.Errorf("region cannot be empty")
		}
		m.state.Region = v
	case stepStage:
		v := strings.TrimSpace(m.stage.Value())
		if v == "" {
			return fmt.Errorf("stage cannot be empty")
		}
		m.state.Stage = v
	case stepScope:
		m.state.Scope = []string{"create-vpc", "import-vpc", "skip-cfn"}[m.scopeIdx]
	case stepVPC:
		if m.state.Scope == "import-vpc" {
			id := ""
			if len(m.vpcs) > 0 && m.vpcCursor >= 0 {
				id = m.vpcs[m.vpcCursor].ID
			} else {
				id = strings.TrimSpace(m.vpcID.Value())
			}
			if id == "" {
				return fmt.Errorf("VPC ID required when importing")
			}
			m.state.VPCID = id
		}
	case stepSubnets:
		if m.state.Scope == "import-vpc" {
			var picked []string
			if len(m.subnets) > 0 {
				for _, s := range m.subnets {
					if m.subnetSelected[s.ID] {
						picked = append(picked, s.ID)
					}
				}
			} else {
				ids := strings.Split(strings.TrimSpace(m.subnetIDs.Value()), ",")
				for _, id := range ids {
					if id = strings.TrimSpace(id); id != "" {
						picked = append(picked, id)
					}
				}
			}
			if len(picked) < 2 {
				return fmt.Errorf("at least 2 subnet IDs required (different AZs)")
			}
			m.state.SubnetIDs = picked
		}
	case stepEndpoints:
		// Already pre-populated from defaults; nothing to validate in v1.
	case stepSource:
		m.state.Source = []string{"published", "build", "ecr-mirror"}[m.sourceIdx]
	case stepTLS:
		m.state.TLSMode = []string{"none", "self-signed", "pca-import", "pca-create"}[m.tlsIdx]
	case stepEKSAccess:
		v := strings.TrimSpace(m.eksARN.Value())
		if v != "" {
			parts := strings.Split(v, ",")
			for _, p := range parts {
				if p = strings.TrimSpace(p); p != "" {
					m.state.AdditionalAccessARNs = append(m.state.AdditionalAccessARNs, p)
				}
			}
		}
	}
	return nil
}

func (m *Model) complete() tea.Cmd {
	st := m.state
	return func() tea.Msg { return CompletedMsg{State: st} }
}

// SetIdentity records the AWS principal — used for display on stepIdentity.
func (m *Model) SetIdentity(arn string) { m.state.Identity = arn }

// SetVPCs is called by root when async VPC detection lands.
func (m *Model) SetVPCs(vs []feature.VPC) {
	m.vpcs = vs
	if len(vs) > 0 && m.vpcCursor < 0 {
		m.vpcCursor = 0
	}
}

// SetSubnets ditto for subnet detection.
func (m *Model) SetSubnets(ss []feature.Subnet) {
	m.subnets = ss
	// Pre-select the first non-isolated subnet in two different AZs.
	seenAZ := map[string]bool{}
	for _, s := range ss {
		if s.RouteLabel != feature.RouteIsolated && !seenAZ[s.AZ] {
			m.subnetSelected[s.ID] = true
			seenAZ[s.AZ] = true
			if len(seenAZ) >= 2 {
				break
			}
		}
	}
}

// SetVPCEndpoints consumes detected endpoints and trims the
// to-be-created list to ONLY the required ones that aren't already
// installed. The required set comes from bootstrap.sh: s3 (gateway),
// ecr, ecrDocker.
func (m *Model) SetVPCEndpoints(eps []feature.VPCEndpoint) {
	m.endpoints = eps
	required := map[string]bool{"s3": true, "ecr": true, "ecrDocker": true}
	installed := map[string]bool{}
	for _, e := range eps {
		if e.ShortName != "" {
			installed[e.ShortName] = true
		}
	}
	var toCreate []string
	for name := range required {
		if !installed[name] {
			toCreate = append(toCreate, name)
		}
	}
	sort.Strings(toCreate)
	m.state.VPCEndpoints = toCreate
}

// View renders the step list + active step.
func (m *Model) View() string {
	s := m.c.Styles
	var b strings.Builder
	b.WriteString(s.Header.Title.Render("Setup"))
	b.WriteString("\n\n")

	for i, sd := range stepDefs {
		// Hide skipped steps so the list reflects the actual flow.
		if i != m.cur && m.skipStep(i) {
			continue
		}
		marker := "  "
		st := s.List.Item
		switch {
		case i < m.cur:
			marker = "✓ "
			st = s.Status.Success
		case i == m.cur:
			marker = "▸ "
			st = s.List.Selected
		}
		num := fmt.Sprintf("%2d ", i+1)
		b.WriteString(num + marker + st.Render(sd.label))
		switch {
		case i < m.cur:
			b.WriteString(s.Header.Subtle.Render("   " + m.stepSummary(i)))
		case i == m.cur:
			b.WriteString("\n")
			b.WriteString(m.activeStepView())
		default:
			b.WriteString(s.Header.Subtle.Render("   " + sd.help))
		}
		b.WriteString("\n")
	}

	if m.err != "" {
		b.WriteString("\n" + s.Form.Error.Render("error: "+m.err))
	}
	b.WriteString("\n" + s.Footer.Hint.Render("[esc] prev   [enter] next   [f1] info   [s] save & exit"))
	if m.showInfo {
		b.WriteString("\n\n" + s.Form.Label.Render("ⓘ "+stepDefs[m.cur].label+":") + "\n")
		for _, line := range stepInfo(m.cur) {
			b.WriteString("  " + s.Header.Subtle.Render(line) + "\n")
		}
	}
	return s.Page.Container.Render(b.String())
}

func (m *Model) stepSummary(i int) string {
	switch i {
	case stepIdentity:
		if m.state.Identity == "" {
			return "(not detected)"
		}
		return shortARN(m.state.Identity)
	case stepRegion:
		return m.state.Region
	case stepStage:
		return m.state.Stage
	case stepScope:
		return m.state.Scope
	case stepVPC:
		if m.state.VPCID == "" {
			return "(not set)"
		}
		return m.state.VPCID
	case stepSubnets:
		if len(m.state.SubnetIDs) == 0 {
			return "(none)"
		}
		return strings.Join(m.state.SubnetIDs, ",")
	case stepEndpoints:
		return strings.Join(m.state.VPCEndpoints, ",")
	case stepSource:
		return m.state.Source
	case stepTLS:
		return m.state.TLSMode
	case stepEKSAccess:
		if len(m.state.AdditionalAccessARNs) == 0 {
			return "(none — caller-only)"
		}
		return strings.Join(m.state.AdditionalAccessARNs, ",")
	case stepAdvanced:
		return "(defaults)"
	}
	return ""
}

func (m *Model) activeStepView() string {
	s := m.c.Styles
	var b strings.Builder
	b.WriteString("    ")
	switch m.cur {
	case stepIdentity:
		if m.state.Identity == "" {
			b.WriteString(s.Status.Warn.Render("waiting for sts:GetCallerIdentity… (press [enter] to continue with whatever lands)"))
		} else {
			b.WriteString(s.List.Selected.Render(m.state.Identity))
		}
	case stepRegion:
		b.WriteString("Region: ")
		b.WriteString(m.region.View())
	case stepStage:
		b.WriteString("Stage: ")
		b.WriteString(m.stage.View())
	case stepScope:
		opts := []string{"Create VPC (recommended)", "Import existing VPC", "Skip CFN deploy"}
		for i, o := range opts {
			marker := "  "
			st := s.List.Item
			if i == m.scopeIdx {
				marker = "▸ "
				st = s.List.Selected
			}
			b.WriteString("\n      " + marker + st.Render(o))
		}
	case stepVPC:
		if len(m.vpcs) > 0 && m.vpcCursor >= 0 {
			b.WriteString(s.Header.Subtle.Render(fmt.Sprintf("Detected %d VPCs in %s — [↑↓] navigate, [m] manual entry:\n", len(m.vpcs), m.state.Region)))
			for i, v := range m.vpcs {
				marker := "  "
				st := s.List.Item
				if i == m.vpcCursor {
					marker = "▸ "
					st = s.List.Selected
				}
				name := v.Name
				if name == "" {
					name = "(unnamed)"
				}
				if v.IsDefault {
					name += " [default]"
				}
				b.WriteString(fmt.Sprintf("      %s%s — %s — %s\n", marker, st.Render(v.ID), name, v.CIDR))
			}
		} else {
			b.WriteString("VPC ID: ")
			b.WriteString(m.vpcID.View())
			if len(m.vpcs) == 0 {
				b.WriteString("\n      ")
				b.WriteString(s.Status.Warn.Render("(no VPCs auto-detected — paste an ID, or check your AWS credentials)"))
			}
		}
	case stepSubnets:
		if len(m.subnets) > 0 {
			b.WriteString(s.Header.Subtle.Render(fmt.Sprintf("Detected %d subnets — [↑↓] navigate, [space] toggle:\n", len(m.subnets))))
			for i, sn := range m.subnets {
				marker := "  "
				if i == m.subnetCursor {
					marker = "▸ "
				}
				box := "[ ]"
				if m.subnetSelected[sn.ID] {
					box = "[x]"
				}
				label := fmt.Sprintf("%s %s %s  %s  %s", box, sn.ID, sn.AZ, sn.CIDR, sn.RouteLabel)
				st := s.List.Item
				if sn.RouteLabel == feature.RouteIsolated {
					st = s.Status.Warn
				}
				if i == m.subnetCursor {
					st = s.List.Selected
				}
				b.WriteString("      " + marker + st.Render(label) + "\n")
			}
		} else {
			b.WriteString("Subnet IDs (comma-separated): ")
			b.WriteString(m.subnetIDs.View())
		}
	case stepEndpoints:
		if len(m.endpoints) == 0 {
			b.WriteString(s.Header.Subtle.Render("To create: " + strings.Join(m.state.VPCEndpoints, ",") + " (no existing endpoints detected)"))
		} else {
			b.WriteString(s.Header.Subtle.Render(fmt.Sprintf("%d existing endpoints detected; will create only missing ones:", len(m.endpoints))))
			b.WriteString("\n      ")
			for _, e := range m.endpoints {
				label := e.ShortName
				if label == "" {
					label = e.ServiceName
				}
				b.WriteString("  ✓ " + label)
			}
			b.WriteString("\n      ")
			if len(m.state.VPCEndpoints) == 0 {
				b.WriteString(s.Status.Success.Render("All required endpoints (s3, ecr, ecrDocker) already exist — nothing to create."))
			} else {
				b.WriteString("To create: " + strings.Join(m.state.VPCEndpoints, ","))
			}
		}
	case stepSource:
		opts := []string{"Latest published", "Specific version", "Build from source"}
		for i, o := range opts {
			marker := "  "
			st := s.List.Item
			if i == m.sourceIdx {
				marker = "▸ "
				st = s.List.Selected
			}
			b.WriteString("\n      " + marker + st.Render(o))
		}
	case stepTLS:
		opts := []string{"none", "self-signed (recommended)", "import existing PCA", "create new PCA (irreversible 7-day delete)"}
		for i, o := range opts {
			marker := "  "
			st := s.List.Item
			if i == m.tlsIdx {
				marker = "▸ "
				st = s.List.Selected
			}
			b.WriteString("\n      " + marker + st.Render(o))
		}
	case stepEKSAccess:
		b.WriteString("Additional access principal ARNs (comma-separated, blank = none): ")
		b.WriteString(m.eksARN.View())
		b.WriteString("\n      ")
		b.WriteString(s.Header.Subtle.Render("Your current caller is granted AmazonEKSClusterAdminPolicy automatically (matches aws-bootstrap.sh)."))
	case stepAdvanced:
		b.WriteString(s.Header.Subtle.Render("(advanced overrides — defaults shown; press enter to keep)"))
	}
	return b.String()
}

// CurrentState exposes the in-flight state for tests / Review.
func (m *Model) CurrentState() State { return m.state }

// stackNameForStage returns "MA-<TitleCase(stage)>" so the CFN stack
// name tracks the stage. aws-bootstrap.sh's effective convention is
// stage 'dev' → stack 'MA-Dev'; we follow that so a fresh stage gets a
// fresh stack and exports don't collide.
func stackNameForStage(stage string) string {
	if stage == "" {
		return "MA"
	}
	var b strings.Builder
	b.WriteString("MA-")
	upperNext := true
	for _, r := range stage {
		switch {
		case r >= '0' && r <= '9':
			b.WriteRune(r)
			upperNext = false
		case r >= 'a' && r <= 'z':
			if upperNext {
				b.WriteRune(r - 32)
			} else {
				b.WriteRune(r)
			}
			upperNext = false
		case r >= 'A' && r <= 'Z':
			b.WriteRune(r)
			upperNext = false
		case r == '-' || r == '_':
			upperNext = true
			b.WriteRune('-')
			// drop other chars (CFN names: alphanumeric + dash only)
		}
	}
	return b.String()
}

// shortARN returns the trailing component of an ARN for compact display.
//
//	"arn:aws:iam::123456789012:user/Admin" → "user/Admin"
//	"arn:aws:iam::123456789012:role/MyRole" → "role/MyRole"
//	"" → ""
func shortARN(arn string) string {
	if arn == "" {
		return ""
	}
	if i := strings.LastIndex(arn, ":"); i > 0 && i < len(arn)-1 {
		return arn[i+1:]
	}
	return arn
}
