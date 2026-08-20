//go:build !linux || !cgo

package vendorsdk

import "errors"

func Probe(_ string, _ []string) error { return errors.New("runtime SDK loading requires Linux CGO build") }
func Enabled() bool { return false }
