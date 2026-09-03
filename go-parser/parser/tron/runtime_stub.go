//go:build !linux || !cgo

package tron

import "errors"

type platformRuntime struct{}

func (platformRuntime) open(string) (uintptr, error) {
	return 0, errors.New("Linux CGO runtime is required")
}
func (platformRuntime) close(uintptr) {}
func (platformRuntime) metadata(uintptr) (metadata, error) {
	return metadata{}, errors.New("Linux CGO runtime is required")
}
func (platformRuntime) readTile(uintptr, uint32, uint32, uint32, uint32) ([]byte, error) {
	return nil, errors.New("Linux CGO runtime is required")
}
func (platformRuntime) readNamed(uintptr, string) ([]byte, error) {
	return nil, errors.New("Linux CGO runtime is required")
}
