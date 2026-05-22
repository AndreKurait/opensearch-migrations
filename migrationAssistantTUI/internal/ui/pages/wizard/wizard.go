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
// the flow only read this struct. JSON tags exist so the [s] save&exit
// path can persist+restore mid-wizard sessions.
type State struct {
	Identity string `json:"identity,omitempty"` // sts:GetCallerIdentity → user/role ARN
	Region   string `json:"region,omitempty"`
	Stage    string `json:"stage,omitempty"`

	// Deployment scope: "create-vpc" | "import-vpc" | "skip-cfn"
	Scope string `json:"scope,omitempty"`

	VPCID        string   `json:"vpc_id,omitempty"`
	SubnetIDs    []string `json:"subnet_ids,omitempty"`
	VPCEndpoints []string `json:"vpc_endpoints,omitempty"`

	Source  string `json:"source,omitempty"`
	Version string `json:"version,omitempty"`
	TLSMode string `json:"tls_mode,omitempty"`

	EKSAccessARN         string   `json:"eks_access_arn,omitempty"`
	AdditionalAccessARNs []string `json:"additional_access_arns,omitempty"`
	Namespace            string   `json:"namespace,omitempty"`
	StackName            string   `json:"stack_name,omitempty"`

	// Advanced overrides — flat map per UX.md §0.1.
	Advanced map[string]string `json:"advanced,omitempty"`

	IgnoreChecks bool `json:"ignore_checks,omitempty"`
}

// CompletedMsg is fired when the wizard is finalized.
type CompletedMsg struct{ State State }

// SaveExitMsg signals the user wants to write the current wizard state
// to disk and quit. Root persists into the workdir's .ma-session.json
// and then sends tea.Quit.
//
// Cur is the step the user was on when they pressed [s]; the next
// launch jumps directly there so resume feels instant.
type SaveExitMsg struct {
	State State
	Cur   int
}

// SetCur jumps the wizard to step `idx`. Clamped to the valid range.
// Used by the saved-session restore path so resume picks up exactly
// where the user left off.
func (m *Model) SetCur(idx int) {
	if idx < 0 {
		idx = 0
	}
	if idx >= stepCount {
		idx = stepCount - 1
	}
	m.cur = idx
}

// LoadState seeds the wizard from a previously-saved session.
func (m *Model) LoadState(st State) {
	m.state = st
	// Restore the nil-safe Advanced map even when older sessions don't
	// persist one — code downstream treats nil as a programming error.
	if m.state.Advanced == nil {
		m.state.Advanced = map[string]string{}
	}
	// Re-derive StackName from Stage so older saved sessions that
	// pre-date the stage→stack-name binding still produce the right
	// CFN stack on resume.
	if st.Stage != "" && st.StackName == "" {
		m.state.StackName = stackNameForStage(st.Stage)
	}
	if st.Region != "" {
		m.region.SetValue(st.Region)
	}
	if st.Stage != "" {
		m.stage.SetValue(st.Stage)
	}
	if st.VPCID != "" {
		m.vpcID.SetValue(st.VPCID)
	}
	if len(st.AdditionalAccessARNs) > 0 {
		m.eksARN.SetValue(strings.Join(st.AdditionalAccessARNs, ","))
	}
	// Re-hydrate the subnet checkbox map so the picker reflects the
	// previously-saved selection. SetSubnets later may add/remove
	// entries, but their checked state survives.
	if m.subnetSelected == nil {
		m.subnetSelected = map[string]bool{}
	}
	for _, id := range st.SubnetIDs {
		m.subnetSelected[id] = true
	}
	switch st.Scope {
	case "create-vpc":
		m.scopeIdx = 0
	case "import-vpc":
		m.scopeIdx = 1
	case "skip-cfn":
		m.scopeIdx = 2
	}
	switch st.Source {
	case "published":
		m.sourceIdx = 0
	case "build":
		m.sourceIdx = 1
	case "ecr-mirror":
		m.sourceIdx = 2
	}
	switch st.TLSMode {
	case "none":
		m.tlsIdx = 0
	case "self-signed":
		m.tlsIdx = 1
	case "pca-import":
		m.tlsIdx = 2
	case "pca-create":
		m.tlsIdx = 3
	}
}

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

	// Step inputs.
	//
	// stackName and namespace are NOT active wizard textinputs in v1 —
	// the values are derived from Stage and pinned to "ma" respectively.
	// We still hold m.state.StackName / m.state.Namespace so the saved
	// session can persist user-edited values from the Advanced step
	// when v2 wires it.
	stage     textinput.Model
	region    textinput.Model
	vpcID     textinput.Model
	subnetIDs textinput.Model
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

	// Filter state — populated when the user presses [/] on a list step.
	// Empty string == no filter.
	filter        string
	filterEditing bool

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
		Namespace:    "ma",
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
		gk := m.c.Keys.Global
		// Save&exit only fires on steps WITHOUT a focused textinput,
		// otherwise typing 'arn:aws:...' on the EKS-access step would
		// quit on the first 's'.
		if key.Matches(v, gk.SaveExit) && !m.stepHasTextInput(m.cur) && !m.filterEditing {
			st := m.state
			cur := m.cur
			return m, func() tea.Msg { return SaveExitMsg{State: st, Cur: cur} }
		}
		// While editing a filter, defer ALL keys to dispatchStep so
		// global hotkeys like [esc] (PrevStep) and [enter] (NextStep)
		// route through the filter's own handlers instead of stealing
		// from the user.
		if m.filterEditing {
			return m.dispatchStep(v)
		}
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

// filteredVPCs returns m.vpcs filtered by the current substring filter.
// Empty filter returns the full slice. Match is case-insensitive on
// the VPC ID, name, and CIDR.
func (m *Model) filteredVPCs() []feature.VPC {
	if m.filter == "" {
		return m.vpcs
	}
	needle := strings.ToLower(m.filter)
	out := make([]feature.VPC, 0, len(m.vpcs))
	for _, v := range m.vpcs {
		if strings.Contains(strings.ToLower(v.ID), needle) ||
			strings.Contains(strings.ToLower(v.Name), needle) ||
			strings.Contains(strings.ToLower(v.CIDR), needle) {
			out = append(out, v)
		}
	}
	return out
}

// filteredSubnets returns m.subnets filtered the same way (id, AZ, CIDR,
// route label).
func (m *Model) filteredSubnets() []feature.Subnet {
	if m.filter == "" {
		return m.subnets
	}
	needle := strings.ToLower(m.filter)
	out := make([]feature.Subnet, 0, len(m.subnets))
	for _, s := range m.subnets {
		if strings.Contains(strings.ToLower(s.ID), needle) ||
			strings.Contains(strings.ToLower(s.AZ), needle) ||
			strings.Contains(strings.ToLower(s.CIDR), needle) ||
			strings.Contains(strings.ToLower(string(s.RouteLabel)), needle) {
			out = append(out, s)
		}
	}
	return out
}

// clampToFiltered keeps a cursor in range when the filtered list shrinks.
func clampToFiltered(cur, n int) int {
	if n == 0 {
		return 0
	}
	if cur < 0 {
		return 0
	}
	if cur >= n {
		return n - 1
	}
	return cur
}

// stepHasTextInput reports whether the given step focuses a textinput
// (so global single-letter shortcuts like [s] should NOT be consumed by
// the wizard). Returns true for region/stage/vpc/subnets/eks-access and
// false for picker steps.
func (m *Model) stepHasTextInput(idx int) bool {
	switch idx {
	case stepRegion, stepStage, stepEKSAccess:
		return true
	case stepVPC:
		// Manual entry mode (vpcCursor < 0) focuses the textinput.
		return len(m.vpcs) == 0 || m.vpcCursor < 0
	case stepSubnets:
		// Manual entry path: no detected subnets → focus textinput.
		return len(m.subnets) == 0
	}
	return false
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
	kk := m.c.Keys.Wizard
	left := key.NewBinding(key.WithKeys("left", "h"))
	right := key.NewBinding(key.WithKeys("right", "l"))

	// Filter editing — applies only on list-driven steps (VPC, subnets).
	if m.filterEditing {
		switch k.String() {
		case "esc":
			m.filterEditing = false
			m.filter = ""
			m.vpcCursor = clampToFiltered(m.vpcCursor, len(m.filteredVPCs()))
			m.subnetCursor = clampToFiltered(m.subnetCursor, len(m.filteredSubnets()))
			return m, nil
		case "enter":
			m.filterEditing = false
			return m, nil
		case "backspace":
			if n := len(m.filter); n > 0 {
				m.filter = m.filter[:n-1]
			}
			return m, nil
		}
		// Append printable char.
		if k.Text != "" {
			m.filter += k.Text
			m.vpcCursor = clampToFiltered(m.vpcCursor, len(m.filteredVPCs()))
			m.subnetCursor = clampToFiltered(m.subnetCursor, len(m.filteredSubnets()))
		}
		return m, nil
	}

	// [/] enters filter mode on list steps.
	if key.Matches(k, kk.Filter) && (m.cur == stepVPC || m.cur == stepSubnets) {
		m.filterEditing = true
		m.filter = ""
		return m, nil
	}

	var cmd tea.Cmd
	switch m.cur {
	case stepIdentity:
		// No edits — informational.
	case stepRegion:
		m.region, cmd = m.region.Update(k)
	case stepStage:
		m.stage, cmd = m.stage.Update(k)
	case stepScope:
		switch {
		case key.Matches(k, kk.Up), key.Matches(k, left):
			if m.scopeIdx > 0 {
				m.scopeIdx--
			}
		case key.Matches(k, kk.Down), key.Matches(k, right):
			if m.scopeIdx < 2 {
				m.scopeIdx++
			}
		}
	case stepVPC:
		// If we have detected VPCs, up/down navigates the list; 'm' switches to manual.
		filtered := m.filteredVPCs()
		if len(filtered) > 0 && m.vpcCursor >= 0 {
			switch {
			case key.Matches(k, kk.Up):
				if m.vpcCursor > 0 {
					m.vpcCursor--
				}
				return m, nil
			case key.Matches(k, kk.Down):
				if m.vpcCursor < len(filtered)-1 {
					m.vpcCursor++
				}
				return m, nil
			case key.Matches(k, kk.Manual):
				m.vpcCursor = -1
				return m, nil
			}
		}
		m.vpcID, cmd = m.vpcID.Update(k)
	case stepSubnets:
		// If we have detected subnets, up/down navigates and space toggles.
		filtered := m.filteredSubnets()
		if len(filtered) > 0 {
			switch {
			case key.Matches(k, kk.Up):
				if m.subnetCursor > 0 {
					m.subnetCursor--
				}
				return m, nil
			case key.Matches(k, kk.Down):
				if m.subnetCursor < len(filtered)-1 {
					m.subnetCursor++
				}
				return m, nil
			case key.Matches(k, kk.Toggle):
				id := filtered[m.subnetCursor].ID
				m.subnetSelected[id] = !m.subnetSelected[id]
				return m, nil
			}
		}
		m.subnetIDs, cmd = m.subnetIDs.Update(k)
	case stepSource:
		switch {
		case key.Matches(k, kk.Up):
			if m.sourceIdx > 0 {
				m.sourceIdx--
			}
		case key.Matches(k, kk.Down):
			if m.sourceIdx < 2 {
				m.sourceIdx++
			}
		}
	case stepTLS:
		switch {
		case key.Matches(k, kk.Up):
			if m.tlsIdx > 0 {
				m.tlsIdx--
			}
		case key.Matches(k, kk.Down):
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
		m.state.StackName = stackNameForStage(v)
	case stepScope:
		m.state.Scope = []string{"create-vpc", "import-vpc", "skip-cfn"}[m.scopeIdx]
	case stepVPC:
		if m.state.Scope == "import-vpc" {
			id := ""
			filtered := m.filteredVPCs()
			if len(filtered) > 0 && m.vpcCursor >= 0 && m.vpcCursor < len(filtered) {
				id = filtered[m.vpcCursor].ID
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
		// Reset before re-parsing so revisiting the step replaces, not appends.
		m.state.AdditionalAccessARNs = nil
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
	b.WriteString("\n" + s.Footer.Hint.Render("[esc] prev   [enter] next   [f1] info   [q] quit"))
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
		filtered := m.filteredVPCs()
		if len(filtered) > 0 && m.vpcCursor >= 0 {
			hint := fmt.Sprintf("Detected %d VPCs in %s — [↑↓] navigate, [/] filter, [m] manual entry:", len(filtered), m.state.Region)
			if m.filter != "" || m.filterEditing {
				hint = fmt.Sprintf("Filter '%s' — %d/%d VPCs match. [enter] confirm, [esc] clear:", m.filter, len(filtered), len(m.vpcs))
			}
			b.WriteString(s.Header.Subtle.Render(hint + "\n"))
			for i, v := range filtered {
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
			} else if m.filter != "" {
				b.WriteString("\n      ")
				b.WriteString(s.Status.Warn.Render(fmt.Sprintf("(filter '%s' matches no VPCs — press [esc] to clear)", m.filter)))
			}
		}
	case stepSubnets:
		filtered := m.filteredSubnets()
		if len(filtered) > 0 {
			hint := fmt.Sprintf("Detected %d subnets — [↑↓] navigate, [/] filter, [space] toggle:", len(filtered))
			if m.filter != "" || m.filterEditing {
				hint = fmt.Sprintf("Filter '%s' — %d/%d subnets match. [enter] confirm, [esc] clear:", m.filter, len(filtered), len(m.subnets))
			}
			b.WriteString(s.Header.Subtle.Render(hint + "\n"))
			for i, sn := range filtered {
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
			if m.filter != "" && len(m.subnets) > 0 {
				b.WriteString("\n      ")
				b.WriteString(s.Status.Warn.Render(fmt.Sprintf("(filter '%s' matches no subnets — press [esc] to clear)", m.filter)))
			}
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
