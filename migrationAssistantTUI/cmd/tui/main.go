// Package main is the migration-assistant TUI entry point.
//
// Lifecycle (PLAN §9.2):
//
//	main → log.Setup (file-only) → app.New(ctx) → tea.Program.Run() → app.Shutdown
//
// Top-level recover restores the terminal before the panic stack
// escapes (PLAN §6.4).
//
// Post-TUI handoff:
//
//	The TUI emits handoff.HandoffMsg right before tea.Quit. main.go
//	captures that signal via the App's HandoffSink, and after the
//	program exits cleanly, exec()s the agent CLI or kubectl per
//	UX.md §11/§12. The TUI is gone before the exec — it does not
//	stay alive (locked **r5**).
package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"runtime/debug"
	"strings"
	"syscall"

	tea "charm.land/bubbletea/v2"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/app"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/log"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui"
)

// version is overridden by ldflags at release time.
var version = "0.0.0-dev"

func main() {
	os.Exit(run())
}

func run() (exit int) {
	// --version short-circuit must not allocate a TUI.
	for _, a := range os.Args[1:] {
		if a == "--version" || a == "-v" {
			fmt.Println(version)
			return 0
		}
		if a == "--help" || a == "-h" {
			fmt.Println(usage)
			return 0
		}
	}

	logFile, err := log.Setup(false)
	if err != nil {
		fmt.Fprintf(os.Stderr, "log setup failed: %v\n", err)
		return 2
	}
	defer logFile.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	a, err := app.New(ctx, app.Config{Version: version})
	if err != nil {
		fmt.Fprintf(os.Stderr, "app init failed: %v\n", err)
		return 2
	}
	defer a.Shutdown()

	root := ui.New(a.Workspace())
	prog := tea.NewProgram(root, tea.WithContext(ctx))
	a.AttachProgram(prog)

	defer func() {
		if r := recover(); r != nil {
			_ = prog.RestoreTerminal()
			log.Panic(r, debug.Stack())
			fmt.Fprintf(os.Stderr, "fatal: %v\n", r)
			exit = 2
		}
	}()

	if _, err := prog.Run(); err != nil {
		log.Errorf("program exited with error: %v", err)
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		return 1
	}

	// Post-TUI: if the user committed to a handoff, syscall.Exec into it.
	// We do this AFTER tea.Program.Run returns so the alt-screen has been
	// fully restored. The TUI process is replaced by kubectl / agent CLI.
	if cmd := a.HandoffCommand(); cmd.Bin != "" {
		// Run any pre-exec setup (e.g. `aws eks update-kubeconfig`) first.
		for _, step := range cmd.PreExec {
			if step.Description != "" {
				fmt.Fprintf(os.Stderr, "\n\u2192 %s\n  $ %s %s\n\n", step.Description, step.Bin, strings.Join(step.Args, " "))
			}
			pre := exec.Command(step.Bin, step.Args...)
			pre.Stdout = os.Stdout
			pre.Stderr = os.Stderr
			if err := pre.Run(); err != nil {
				fmt.Fprintf(os.Stderr, "pre-exec %s failed: %v\n", step.Bin, err)
				return 1
			}
		}
		bin, err := lookPath(cmd.Bin)
		if err != nil {
			fmt.Fprintf(os.Stderr, "handoff: %v\n", err)
			return 1
		}
		// Best-effort cd into workdir.
		if cmd.Cwd != "" {
			_ = os.Chdir(cmd.Cwd)
		}
		argv := append([]string{cmd.Bin}, cmd.Args...)
		if err := syscall.Exec(bin, argv, os.Environ()); err != nil {
			fmt.Fprintf(os.Stderr, "exec %s: %v\n", bin, err)
			return 1
		}
	}
	return 0
}

const usage = `migration-assistant — OpenSearch Migration Assistant TUI

Usage:
  migration-assistant            launch the TUI
  migration-assistant --version  print version and exit
  migration-assistant --help     show this help

State lives under your launch cwd in opensearch-migration-<account>-<region>/.
See UX.md §0.4 for the workspace layout.`

// lookPath defers to exec.LookPath to resolve PATH entries; isolated so
// tests can override it.
var lookPath = func(name string) (string, error) {
	return execLookPath(name)
}
