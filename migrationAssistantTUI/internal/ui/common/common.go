// Package common holds the shared context every page receives: styles,
// workspace, current size. Constructed once by root and passed by pointer.
package common

import (
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/keys"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/styles"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/workspace"
)

// Common is passed to every page Model. Pages read styles, workspace,
// and the current width/height; they NEVER mutate this struct.
type Common struct {
	WS     workspace.Workspace
	Styles *styles.Styles
	Keys   keys.KeyMap

	Width  int
	Height int
}

// New constructs a Common with the given workspace, dark theme, default
// keys, and zero dimensions. Caller updates Width/Height on LayoutMsg.
func New(ws workspace.Workspace) *Common {
	return &Common{
		WS:     ws,
		Styles: styles.ByName(ws.Config().Theme),
		Keys:   keys.Default(),
	}
}
