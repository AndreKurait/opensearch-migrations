package main

import "os/exec"

// execLookPath aliases exec.LookPath so the test build can swap it.
func execLookPath(name string) (string, error) { return exec.LookPath(name) }
