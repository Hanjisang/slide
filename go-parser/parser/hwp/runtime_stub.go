//go:build !linux || !cgo

package hwp

import "errors"

func runtimeOpen(string) (uintptr, error) { return 0, errors.New("HWP SDK requires Linux CGO") }
func runtimeClose(uintptr)                {}
func runtimeConfig(uintptr) (config, error) {
	return config{}, errors.New("HWP SDK requires Linux CGO")
}
func runtimeReadImage(uintptr, uint32, uint32, float32) ([]byte, uint32, uint32, error) {
	return nil, 0, 0, errors.New("HWP SDK requires Linux CGO")
}
func runtimeReadNamed(uintptr, int) ([]byte, uint32, uint32, error) {
	return nil, 0, 0, errors.New("HWP SDK requires Linux CGO")
}
