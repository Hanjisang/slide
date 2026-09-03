//go:build !linux

package hwp

import "errors"

type platformRuntime struct{}

func (platformRuntime) open(string) (uintptr, error) {
	return 0, errors.New("Linux HWP helper runtime is required")
}
func (platformRuntime) close(uintptr) {}
func (platformRuntime) config(uintptr) (config, error) {
	return config{}, errors.New("Linux HWP helper runtime is required")
}
func (platformRuntime) readImage(uintptr, uint32, uint32, float32) ([]byte, uint32, uint32, error) {
	return nil, 0, 0, errors.New("Linux HWP helper runtime is required")
}
func (platformRuntime) readNamed(uintptr, int) ([]byte, uint32, uint32, error) {
	return nil, 0, 0, errors.New("Linux HWP helper runtime is required")
}
