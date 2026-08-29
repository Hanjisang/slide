package hwp

import (
	"errors"
	"fmt"
	"io"
	"math"
	"runtime"
	"sync"

	"imageparser/types"
	"imageparser/utils/streamer"
)

const (
	imagePreview = iota
	imageLabel
	imageThumb
)

type config struct {
	tileWidth, tileHeight      uint32
	imageWidth, imageHeight    uint32
	scanRatio, downsample, mpp float32
}

type nativeRuntime interface {
	open(string) (uintptr, error)
	close(uintptr)
	config(uintptr) (config, error)
	readImage(uintptr, uint32, uint32, float32) ([]byte, uint32, uint32, error)
	readNamed(uintptr, int) ([]byte, uint32, uint32, error)
}

type parser struct {
	stream streamer.Streamer
	native nativeRuntime
	reader uintptr
	config config
	header types.HeaderInfo
	mu     sync.Mutex
}

// New opens the optional HWP SDK adapter. Missing SDK files fail this parser
// initialization only; the Go parser service and all other formats remain up.
func New(s streamer.Streamer) (types.ImageParser, error) {
	return newWithRuntime(s, platformRuntime{})
}

func newWithRuntime(s streamer.Streamer, native nativeRuntime) (*parser, error) {
	if s == nil || native == nil {
		return nil, errors.New("invalid HWP parser dependency")
	}
	reader, err := native.open(s.GetFileName())
	if err != nil {
		return nil, fmt.Errorf("HWP_SDK_NOT_AVAILABLE: %w", err)
	}
	cfg, err := native.config(reader)
	if err != nil {
		native.close(reader)
		return nil, fmt.Errorf("HWP config: %w", err)
	}
	if cfg.imageWidth == 0 || cfg.imageHeight == 0 || cfg.imageWidth > 2_000_000 || cfg.imageHeight > 2_000_000 ||
		cfg.tileWidth == 0 || cfg.tileHeight == 0 || cfg.tileWidth > 4096 || cfg.tileHeight > 4096 {
		native.close(reader)
		return nil, fmt.Errorf("invalid HWP config: image %dx%d tile %dx%d", cfg.imageWidth, cfg.imageHeight, cfg.tileWidth, cfg.tileHeight)
	}
	maxLayer := 0
	maxDimension := max(uint64(cfg.imageWidth), uint64(cfg.imageHeight))
	minimumTile := min(uint64(cfg.tileWidth), uint64(cfg.tileHeight))
	for maxDimension > minimumTile && maxLayer < 31 {
		maxDimension = (maxDimension + 1) / 2
		maxLayer++
	}
	p := &parser{stream: s, native: native, reader: reader, config: cfg}
	p.header = types.NewHeaderInfo(s.GetFileName(), 0, maxLayer, int(cfg.imageHeight), int(cfg.imageWidth), cfg.scanRatio, cfg.downsample, 0, 0, cfg.mpp, max(int(cfg.tileWidth), int(cfg.tileHeight)))
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
		return errors.New("invalid HWP tile coordinate")
	}
	shift := p.header.MaxLayer - layer
	scale := uint64(1) << uint(shift)
	levelWidth := (uint64(p.config.imageWidth) + scale - 1) / scale
	levelHeight := (uint64(p.config.imageHeight) + scale - 1) / scale
	cols := (levelWidth + uint64(p.config.tileWidth) - 1) / uint64(p.config.tileWidth)
	rows := (levelHeight + uint64(p.config.tileHeight) - 1) / uint64(p.config.tileHeight)
	if uint64(line) >= cols || uint64(row) >= rows {
		return errors.New("HWP tile coordinate is outside level bounds")
	}
	x := uint64(line) * uint64(p.config.tileWidth)
	y := uint64(row) * uint64(p.config.tileHeight)
	if x > math.MaxUint32 || y > math.MaxUint32 {
		return errors.New("HWP tile origin overflows SDK coordinate")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.reader == 0 {
		return errors.New("HWP reader is closed")
	}
	data, _, _, err := p.native.readImage(p.reader, uint32(x), uint32(y), float32(scale))
	if err != nil {
		return fmt.Errorf("HWP tile: %w", err)
	}
	_, err = w.Write(data)
	return err
}

func (p *parser) GetThumbnailImagePathFunc(w io.Writer) error { return p.writeNamed(w, imageThumb) }
func (p *parser) GetLabelInfoPathFunc(w io.Writer) error      { return p.writeNamed(w, imageLabel) }
func (p *parser) GetMacrograph(w io.Writer) error             { return p.writeNamed(w, imagePreview) }

func (p *parser) writeNamed(w io.Writer, kind int) error {
	if w == nil {
		return errors.New("nil HWP image writer")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.reader == 0 {
		return errors.New("HWP reader is closed")
	}
	data, _, _, err := p.native.readNamed(p.reader, kind)
	if err != nil {
		return err
	}
	_, err = w.Write(data)
	return err
}

var _ types.CloseableParser = (*parser)(nil)
