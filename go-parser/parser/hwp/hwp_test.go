package hwp

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
	closed int
	x, y   uint32
	scale  float32
	kind   int
	cfg    *config
}

func (f *fakeRuntime) open(string) (uintptr, error) { return 1, nil }
func (f *fakeRuntime) close(uintptr)                { f.closed++ }
func (f *fakeRuntime) config(uintptr) (config, error) {
	if f.cfg != nil {
		return *f.cfg, nil
	}
	return config{tileWidth: 256, tileHeight: 256, imageWidth: 1024, imageHeight: 512, scanRatio: 40, downsample: 2, mpp: .25}, nil
}
func (f *fakeRuntime) readImage(_ uintptr, x, y uint32, scale float32) ([]byte, uint32, uint32, error) {
	f.x, f.y, f.scale = x, y, scale
	return []byte("tile"), 256, 256, nil
}
func (f *fakeRuntime) readNamed(_ uintptr, kind int) ([]byte, uint32, uint32, error) {
	f.kind = kind
	return []byte("named"), 1, 1, nil
}

func TestParserCoordinatesLayersAndClose(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.hwp")
	if err := os.WriteFile(path, []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	native := &fakeRuntime{}
	p, err := newWithRuntime(streamer.NewFile(path, binary.LittleEndian), native)
	if err != nil {
		t.Fatal(err)
	}
	header, _ := p.GetHeaderInfoFunc()
	if header.MaxLayer != 2 || header.Width != 1024 || header.Height != 512 {
		t.Fatalf("unexpected header: %+v", header)
	}
	var output bytes.Buffer
	if err := p.GetImage(1, 1, 0, &output); err != nil {
		t.Fatal(err)
	}
	if native.x != 256 || native.y != 0 || native.scale != 2 || output.String() != "tile" {
		t.Fatalf("unexpected native request: x=%d y=%d scale=%v output=%q", native.x, native.y, native.scale, output.String())
	}
	if err := p.GetImage(-1, 0, 0, &bytes.Buffer{}); err == nil {
		t.Fatal("invalid HWP level was accepted")
	}
	if err := p.GetImage(2, 4, 0, &bytes.Buffer{}); err == nil {
		t.Fatal("invalid HWP coordinate was accepted")
	}
	if err := p.GetThumbnailImagePathFunc(&bytes.Buffer{}); err != nil || native.kind != imageThumb {
		t.Fatalf("thumbnail kind=%d err=%v", native.kind, err)
	}
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(); err != nil || native.closed != 1 {
		t.Fatalf("close count=%d err=%v", native.closed, err)
	}
	if err := p.GetImage(2, 0, 0, &bytes.Buffer{}); err == nil {
		t.Fatal("closed reader accepted tile request")
	}
}

func TestParserUsesStoredFrameRatiosAndOrigins(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.hwp")
	if err := os.WriteFile(path, []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := config{tileWidth: 256, tileHeight: 256, imageWidth: 1024, imageHeight: 512, scanRatio: 20, levels: []hwpLevel{
		{width: 1024, height: 512, originX: 32, originY: 64, ratio: 20},
		{width: 256, height: 128, originX: 8, originY: 16, ratio: 5},
	}}
	native := &fakeRuntime{cfg: &cfg}
	p, err := newWithRuntime(streamer.NewFile(path, binary.LittleEndian), native)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	header, _ := p.GetHeaderInfoFunc()
	if header.MaxLayer != 1 || len(header.Levels) != 2 || header.Levels[0].Width != 256 || header.Levels[0].Downsample != 4 {
		t.Fatalf("unexpected stored levels: %+v", header.Levels)
	}
	if err := p.GetImage(0, 0, 0, &bytes.Buffer{}); err != nil {
		t.Fatal(err)
	}
	if native.x != 8 || native.y != 16 || native.scale != 5 {
		t.Fatalf("unexpected stored frame request: x=%d y=%d scale=%v", native.x, native.y, native.scale)
	}
}

type failingRuntime struct{ fakeRuntime }

func (f *failingRuntime) open(string) (uintptr, error) { return 0, errors.New("dlopen failed") }

func TestSDKUnavailableErrorMapping(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.hwp")
	if err := os.WriteFile(path, []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	parser, err := newWithRuntime(streamer.NewFile(path, binary.LittleEndian), &failingRuntime{})
	if err == nil || parser != nil || !strings.Contains(err.Error(), "HWP_SDK_NOT_AVAILABLE") {
		t.Fatalf("unexpected unavailable mapping: parser=%v err=%v", parser, err)
	}
}

func TestNewRejectsIncompatibleMagicBeforeSDKCall(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.hwp")
	if err := os.WriteFile(path, []byte("INVALID!payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	parser, err := New(streamer.NewFile(path, binary.LittleEndian))
	if err == nil || parser != nil || !strings.Contains(err.Error(), "HWP_UNSUPPORTED_MAGIC") {
		t.Fatalf("unexpected incompatible HWP result: parser=%v err=%v", parser, err)
	}
}
