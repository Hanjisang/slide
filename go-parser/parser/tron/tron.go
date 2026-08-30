package tron

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"runtime"
	"sync"

	"imageparser/types"
	"imageparser/utils/streamer"
)

type metadata struct {
	width, height         uint32
	contentX, contentY    uint32
	tileWidth, tileHeight uint32
	lodMin, lodMax        uint32
	layerIndex            uint32
	mppX, mppY            float32
}

type nativeRuntime interface {
	open(string) (uintptr, error)
	close(uintptr)
	metadata(uintptr) (metadata, error)
	readTile(uintptr, uint32, uint32, uint32, uint32) ([]byte, error)
	readNamed(uintptr, string) ([]byte, error)
}

type parser struct {
	stream streamer.Streamer
	native nativeRuntime
	reader uintptr
	meta   metadata
	header types.HeaderInfo
	blank  []byte
	mu     sync.Mutex
}

var errTRONTileMissing = errors.New("TRON tile is not stored")

// New opens TRON through a runtime-loaded vendor library. The adapter ABI is
// isolated here so the service can start normally when the local SDK is absent.
func New(s streamer.Streamer) (types.ImageParser, error) {
	return newWithRuntime(s, platformRuntime{})
}

func newWithRuntime(s streamer.Streamer, native nativeRuntime) (*parser, error) {
	if s == nil || native == nil {
		return nil, errors.New("invalid TRON parser dependency")
	}
	reader, err := native.open(s.GetFileName())
	if err != nil {
		return nil, fmt.Errorf("TRON_SDK_NOT_AVAILABLE: %w", err)
	}
	meta, err := native.metadata(reader)
	if err != nil {
		native.close(reader)
		return nil, fmt.Errorf("TRON metadata: %w", err)
	}
	if meta.width == 0 || meta.height == 0 || meta.width > 2_000_000 || meta.height > 2_000_000 ||
		meta.tileWidth == 0 || meta.tileHeight == 0 || meta.tileWidth > 4096 || meta.tileHeight > 4096 ||
		meta.lodMax < meta.lodMin || meta.lodMax-meta.lodMin >= 32 {
		native.close(reader)
		return nil, fmt.Errorf("invalid TRON metadata: image %dx%d tile %dx%d LOD %d..%d", meta.width, meta.height, meta.tileWidth, meta.tileHeight, meta.lodMin, meta.lodMax)
	}
	mpp := meta.mppX
	if mpp <= 0 {
		mpp = meta.mppY
	}
	blank, err := blankTRONTile(meta.tileWidth, meta.tileHeight)
	if err != nil {
		native.close(reader)
		return nil, err
	}
	p := &parser{stream: s, native: native, reader: reader, meta: meta, blank: blank}
	p.header = types.NewHeaderInfo(s.GetFileName(), 0, int(meta.lodMax-meta.lodMin), int(meta.height), int(meta.width), 0, 2, 0, 0, mpp, max(int(meta.tileWidth), int(meta.tileHeight)))
	runtime.SetFinalizer(p, (*parser).finalize)
	return p, nil
}

func (p *parser) finalize() { _ = p.Close() }

func (p *parser) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.reader != 0 {
		p.native.close(p.reader)
		p.reader = 0
	}
	runtime.SetFinalizer(p, nil)
	return nil
}

func (p *parser) GetFileName() string                          { return p.stream.GetFileName() }
func (p *parser) GetFileSize() int64                           { n, _ := p.stream.GetFileSize(); return n }
func (p *parser) GetDependencies() ([]string, error)           { return []string{p.GetFileName()}, nil }
func (p *parser) GetHeaderInfoFunc() (types.HeaderInfo, error) { return p.header, nil }

func (p *parser) GetImage(layer, line, row int, w io.Writer) error {
	if w == nil || layer < p.header.MinLayer || layer > p.header.MaxLayer || line < 0 || row < 0 {
		return errors.New("invalid TRON tile coordinate")
	}
	shift := p.header.MaxLayer - layer
	scale := uint64(1) << uint(shift)
	levelWidth := (uint64(p.meta.width) + scale - 1) / scale
	levelHeight := (uint64(p.meta.height) + scale - 1) / scale
	cols := (levelWidth + uint64(p.meta.tileWidth) - 1) / uint64(p.meta.tileWidth)
	rows := (levelHeight + uint64(p.meta.tileHeight) - 1) / uint64(p.meta.tileHeight)
	if uint64(line) >= cols || uint64(row) >= rows {
		return errors.New("TRON tile coordinate is outside level bounds")
	}
	// TRON numbers LOD from full resolution toward lower resolutions; the
	// service exposes the inverse, browser-friendly low-to-high order.
	nativeLOD := p.meta.lodMax - uint32(layer)
	// Archive tile coordinates are expressed against the uncropped slide
	// canvas, while the viewer addresses the content region from (0, 0).
	// Preserve the content-region origin and translate into the native grid.
	nativeScale := uint64(1) << nativeLOD
	nativeCol := uint32(uint64(line) + uint64(p.meta.contentX)/(uint64(p.meta.tileWidth)*nativeScale))
	nativeRow := uint32(uint64(row) + uint64(p.meta.contentY)/(uint64(p.meta.tileHeight)*nativeScale))
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.reader == 0 {
		return errors.New("TRON reader is closed")
	}
	data, err := p.native.readTile(p.reader, nativeLOD, p.meta.layerIndex, nativeCol, nativeRow)
	if errors.Is(err, errTRONTileMissing) {
		data, err = p.blank, nil
	}
	if err != nil {
		return fmt.Errorf("TRON tile: %w", err)
	}
	_, err = w.Write(data)
	return err
}

func blankTRONTile(width, height uint32) ([]byte, error) {
	img := image.NewGray(image.Rect(0, 0, int(width), int(height)))
	for i := range img.Pix {
		img.Pix[i] = 0xff
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, img, &jpeg.Options{Quality: 85}); err != nil {
		return nil, fmt.Errorf("encode blank TRON tile: %w", err)
	}
	return output.Bytes(), nil
}

func (p *parser) GetThumbnailImagePathFunc(w io.Writer) error { return p.writeNamed(w, "thumbnail") }
func (p *parser) GetLabelInfoPathFunc(w io.Writer) error      { return p.writeNamed(w, "label") }
func (p *parser) GetMacrograph(w io.Writer) error             { return p.writeNamed(w, "macro") }

func (p *parser) writeNamed(w io.Writer, name string) error {
	if w == nil {
		return errors.New("nil TRON image writer")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.reader == 0 {
		return errors.New("TRON reader is closed")
	}
	data, err := p.native.readNamed(p.reader, name)
	if err != nil && name == "macro" {
		data, err = p.native.readNamed(p.reader, "sample")
	}
	if err != nil {
		return err
	}
	_, err = w.Write(data)
	return err
}

var _ types.CloseableParser = (*parser)(nil)
