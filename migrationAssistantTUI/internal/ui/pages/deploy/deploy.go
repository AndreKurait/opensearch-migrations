// Package deploy is the live deploy view (UX.md §10).
//
// This page is the VIEW layer. The real CFN/helm orchestration lives
// in internal/feature/deploy and runs in a goroutine; PhaseEvents from
// the driver are translated into PhaseEventMsg messages that this page
// consumes via Update.
package deploy

import (
	"errors"
	"fmt"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/spinner"

	deployfeat "github.com/opensearch-project/opensearch-migrations/tui/internal/feature/deploy"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/common"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

// CompletedMsg signals all phases finished successfully.
type CompletedMsg struct{}

// FailedMsg signals the deploy failed.
type FailedMsg struct{ Err error }

// BackgroundedMsg signals the user pressed [esc] to background the
// deploy. The driver goroutine keeps running; root navigates back to
// the welcome screen so the user can see status.
type BackgroundedMsg struct{}

// PhaseEventMsg wraps a feature/deploy.PhaseEvent for delivery via the
// Bubble Tea Update loop. The root model translates broker-published
// PhaseEvents into these.
type PhaseEventMsg struct{ Event deployfeat.PhaseEvent }

// Phase tracks per-phase progress.
type Phase struct {
	Name      string
	Status    string // "PENDING" | "IN_PROGRESS" | "COMPLETE" | "FAILED"
	StartedAt time.Time
	Events    []string
}

// Model is the live deploy state.
type Model struct {
	c *common.Common

	st       wizard.State
	phases   []Phase
	current  int
	spin     spinner.Model
	complete bool
	err      string

	startedAt time.Time // wall-clock start — used for elapsed display
	now       time.Time // last elapsedTick observation

	// allEvents is the full event log shown when [l] toggles the overlay.
	allEvents []logEntry
	showLog   bool
}

// logEntry is one row in the [l] full-log overlay.
type logEntry struct {
	At      time.Time
	Phase   string
	Status  string
	Message string
}

// New constructs the deploy page.
func New(c *common.Common) *Model {
	sp := spinner.New()
	sp.Spinner = spinner.Dot
	return &Model{
		c:    c,
		spin: sp,
	}
}

// Begin initializes the phase board. The actual orchestration is
// driven by the root model (which calls feature/deploy.Driver.Run in a
// goroutine and pumps PhaseEvents back as PhaseEventMsg).
func (m *Model) Begin(st wizard.State) tea.Cmd {
	m.st = st
	m.startedAt = time.Now()
	m.phases = []Phase{
		{Name: "CloudFormation", Status: "PENDING"},
		{Name: "CFN exports", Status: "PENDING"},
		{Name: "kubectl context", Status: "PENDING"},
		{Name: "EKS access", Status: "PENDING"},
		{Name: "Image mirror (ECR)", Status: "PENDING"},
		{Name: "Helm install", Status: "PENDING"},
	}
	m.current = 0
	// Force the first phase into IN_PROGRESS so the user sees activity
	// immediately, before the driver's first event lands.
	m.phases[0].Status = "IN_PROGRESS"
	m.phases[0].StartedAt = m.startedAt
	return tea.Batch(m.spin.Tick, m.elapsedTickCmd())
}

// Init returns nothing (Begin is the entry).
func (m *Model) Init() tea.Cmd { return nil }

// Update handles ticks + key input + phase events.
func (m *Model) Update(message tea.Msg) (*Model, tea.Cmd) {
	switch v := message.(type) {
	case tea.KeyPressMsg:
		kk := m.c.Keys.Deploy
		switch {
		case key.Matches(v, kk.Background):
			// Mark the page complete so the elapsed tick stops, AND emit
			// a BackgroundedMsg so the root model can route back to the
			// welcome screen. The deploy goroutine keeps running.
			m.complete = true
			return m, func() tea.Msg { return BackgroundedMsg{} }
		case key.Matches(v, kk.FullLog):
			m.showLog = !m.showLog
			return m, nil
		}
	case spinner.TickMsg:
		var cmd tea.Cmd
		m.spin, cmd = m.spin.Update(v)
		// Stop the animation once the run is over — otherwise the
		// spinner keeps consuming a goroutine even after Begin's
		// caller has navigated away.
		if m.complete {
			return m, nil
		}
		return m, cmd
	case elapsedTickMsg:
		m.now = time.Time(v)
		if m.complete {
			return m, nil
		}
		return m, m.elapsedTickCmd()
	case PhaseEventMsg:
		failed := v.Event.Status == "failed"
		m.applyEvent(v.Event)
		if failed {
			err := errors.New(v.Event.Message)
			return m, func() tea.Msg { return FailedMsg{Err: err} }
		}
		// When the last phase reports completed, signal completion.
		if m.complete && m.err == "" {
			return m, func() tea.Msg { return CompletedMsg{} }
		}
		return m, nil
	}
	return m, nil
}

// applyEvent maps a feature/deploy.PhaseEvent into the page's Phase[]
// state. Phase names use the deploy driver's labels ("cfn", "exports",
// "kubeconfig", "access", "helm") and are mapped 1:1 to the board rows.
func (m *Model) applyEvent(ev deployfeat.PhaseEvent) {
	// Always log to the full log first — even unrecognized phases land here.
	m.allEvents = append(m.allEvents, logEntry{At: ev.At, Phase: ev.Phase, Status: ev.Status, Message: ev.Message})
	if len(m.allEvents) > 1000 {
		// Cap log to avoid unbounded growth on long deploys.
		m.allEvents = m.allEvents[len(m.allEvents)-1000:]
	}

	idx := -1
	switch ev.Phase {
	case "cfn":
		idx = 0
	case "exports":
		idx = 1
	case "kubeconfig":
		idx = 2
	case "access":
		idx = 3
	case "images":
		idx = 4
	case "helm":
		idx = 5
	}
	if idx < 0 || idx >= len(m.phases) {
		return
	}
	ph := &m.phases[idx]
	switch ev.Status {
	case "started":
		ph.Status = "IN_PROGRESS"
		ph.StartedAt = ev.At
		m.current = idx
	case "progress":
		// Trim noisy lines so the board stays readable.
		line := strings.TrimSpace(ev.Message)
		if len(line) > 200 {
			line = line[:197] + "…"
		}
		ph.Events = append(ph.Events, line)
		// Per-phase event cap: long deploys can spam thousands of CFN
		// progress events into one phase. The view only shows the last 3
		// anyway, but the slice keeps growing unbounded otherwise.
		const maxPerPhase = 50
		if len(ph.Events) > maxPerPhase {
			ph.Events = ph.Events[len(ph.Events)-maxPerPhase:]
		}
	case "completed":
		ph.Status = "COMPLETE"
		// Mark globally complete once every phase reports COMPLETE — robust
		// against future phases being added without updating this branch.
		allDone := true
		for _, p := range m.phases {
			if p.Status != "COMPLETE" {
				allDone = false
				break
			}
		}
		if allDone {
			m.complete = true
		}
	case "failed":
		ph.Status = "FAILED"
		m.err = ev.Message
		m.current = idx
		m.complete = true
	}
}

// elapsedTickMsg is fired every second so the elapsed-time display
// updates even when the driver is silent (e.g. polling CFN with no new
// events).
type elapsedTickMsg time.Time

func (m *Model) elapsedTickCmd() tea.Cmd {
	return tea.Tick(time.Second, func(t time.Time) tea.Msg { return elapsedTickMsg(t) })
}

// View renders the deploy progress board.
func (m *Model) View() string {
	s := m.c.Styles
	var b strings.Builder
	title := "Deploying Migration Assistant"
	switch {
	case m.err != "":
		title = "Deploy failed"
	case m.complete:
		title = "Deploy complete"
	}
	b.WriteString(s.Header.Title.Render(title))
	b.WriteString("\n")

	// Top-line status: total elapsed + active phase pointer. Always
	// renders so the user knows the TUI is alive even when AWS is silent.
	elapsed := time.Duration(0)
	if !m.startedAt.IsZero() {
		now := m.now
		if now.IsZero() {
			now = time.Now()
		}
		elapsed = now.Sub(m.startedAt).Round(time.Second)
	}
	active := "(starting…)"
	if m.current < len(m.phases) {
		active = m.phases[m.current].Name
	}
	if m.complete && m.err == "" {
		active = "✓ all phases complete"
	}
	leadingGlyph := m.spin.View()
	if m.complete {
		leadingGlyph = " " // spinner stops once we're done
	}
	b.WriteString(s.Header.Subtle.Render(fmt.Sprintf("  %s  •  active: %s  •  %s elapsed\n\n",
		leadingGlyph, active, elapsed)))

	for i, ph := range m.phases {
		head := fmt.Sprintf("Phase %d/%d: %s", i+1, len(m.phases), ph.Name)
		st := s.Status.Info
		switch ph.Status {
		case "IN_PROGRESS":
			st = s.Status.Warn
			phaseElapsed := ""
			if !ph.StartedAt.IsZero() {
				now := m.now
				if now.IsZero() {
					now = time.Now()
				}
				phaseElapsed = "  (" + now.Sub(ph.StartedAt).Round(time.Second).String() + ")"
			}
			head = m.spin.View() + " " + head + phaseElapsed
		case "COMPLETE":
			st = s.Status.Success
			head = "✓ " + head
		case "FAILED":
			st = s.Status.Error
			head = "✗ " + head
		default:
			head = "  " + head
		}
		b.WriteString(st.Render(head))
		b.WriteString("\n")
		// Render only the last 3 events to keep the board compact.
		start := 0
		if len(ph.Events) > 3 {
			start = len(ph.Events) - 3
		}
		for _, ev := range ph.Events[start:] {
			b.WriteString("    " + s.Header.Subtle.Render(ev) + "\n")
		}
		if ph.Status == "IN_PROGRESS" && len(ph.Events) == 0 {
			b.WriteString("    " + s.Header.Subtle.Render("(no events yet — waiting on AWS…)") + "\n")
		}
	}
	if m.err != "" {
		b.WriteString("\n" + s.Status.Error.Render("Deploy failed: "+m.err))
		b.WriteString("\n" + s.Status.Warn.Render("Press [esc] to leave; re-run `migration-assistant` to retry."))
	}
	if m.showLog {
		b.WriteString("\n\n" + s.Form.Label.Render("Full log:") + "\n")
		start := 0
		if len(m.allEvents) > 30 {
			start = len(m.allEvents) - 30
		}
		for _, e := range m.allEvents[start:] {
			ts := e.At.Format("15:04:05")
			line := fmt.Sprintf("  %s %-12s %-10s %s", ts, e.Phase, e.Status, e.Message)
			if len(line) > 200 {
				line = line[:197] + "…"
			}
			b.WriteString(s.Header.Subtle.Render(line) + "\n")
		}
		if len(m.allEvents) > 30 {
			b.WriteString(s.Header.Subtle.Render(fmt.Sprintf("  … %d earlier events hidden", len(m.allEvents)-30)) + "\n")
		}
	}
	b.WriteString("\n" + s.Footer.Hint.Render("[l] toggle full log   [esc] background (deploy keeps running)"))
	return s.Page.Container.Render(b.String())
}
