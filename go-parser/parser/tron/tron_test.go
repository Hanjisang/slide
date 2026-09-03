package tron

import (
	"bytes"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"imageparser/utils/streamer"
)

type fakeRuntime struct {
	closed               int
	layer, lod, col, row uint32
	named                string
	missing              bool
}

func (f *fakeRuntime) open(string) (uintptr, error) { return 1, nil }
func (f *fakeRuntime) close(uintptr)                { f.closed++ }
func (f *fakeRuntime) metadata(uintptr) (metadata, error) {
	return metadata{width: 1024, height: 512, contentX: 2048, contentY: 1024, tileWidth: 256, tileHeight: 256, lodMin: 0, lodMax: 2, layerIndex: 3, mppX: .25}, nil
}
func (f *fakeRuntime) readTile(_ uintptr, lod, layer, col, row uint32) ([]byte, error) {
	f.layer, f.lod, f.col, f.row = layer, lod, col, row
	if f.missing {
		return nil, errTRONTileMissing
	}
	return []byte("tile"), nil
}
func (f *fakeRuntime) readNamed(_ uintptr, name string) ([]byte, error) {
	f.named = name
	return []byte("named"), nil
}

func TestParserMapsBrowserLevelToNativeLOD(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.tron")
	if err := os.WriteFile(path, []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	native := &fakeRuntime{}
	p, err := newWithRuntime(streamer.NewFile(path, binary.LittleEndian), native)
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := p.GetImage(2, 3, 1, &output); err != nil {
		t.Fatal(err)
	}
	if native.layer != 3 || native.lod != 0 || native.col != 11 || native.row != 5 || output.String() != "tile" {
		t.Fatalf("unexpected native tile request: %+v output=%q", native, output.String())
	}
	if err := p.GetImage(0, 0, 0, &output); err != nil {
		t.Fatal(err)
	}
	if native.lod != 2 || native.col != 2 || native.row != 1 {
		t.Fatalf("unexpected scaled native tile request: %+v", native)
	}
	native.missing = true
	output.Reset()
	if err := p.GetImage(1, 0, 0, &output); err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(output.Bytes(), []byte{0xff, 0xd8, 0xff}) {
		t.Fatalf("missing native tile did not produce a JPEG: %x", output.Bytes()[:min(8, output.Len())])
	}
	native.missing = false
	if err := p.GetImage(-1, 0, 0, &bytes.Buffer{}); err == nil {
		t.Fatal("invalid TRON level was accepted")
	}
	if err := p.GetImage(2, 4, 0, &bytes.Buffer{}); err == nil {
		t.Fatal("invalid TRON coordinate was accepted")
	}
	if err := p.GetLabelInfoPathFunc(&bytes.Buffer{}); err != nil || native.named != "label" {
		t.Fatalf("named=%q err=%v", native.named, err)
	}
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(); err != nil || native.closed != 1 {
		t.Fatalf("close count=%d err=%v", native.closed, err)
	}
}

type failingRuntime struct{ fakeRuntime }

func (f *failingRuntime) open(string) (uintptr, error) { return 0, errors.New("dlopen failed") }

func TestSDKUnavailableErrorMapping(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.tron")
	if err := os.WriteFile(path, []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	parser, err := newWithRuntime(streamer.NewFile(path, binary.LittleEndian), &failingRuntime{})
	if err == nil || parser != nil || !strings.Contains(err.Error(), "TRON_SDK_NOT_AVAILABLE") {
		t.Fatalf("unexpected unavailable mapping: parser=%v err=%v", parser, err)
	}
}
