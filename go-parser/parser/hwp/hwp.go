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

var errUnavailable = errors.New("HWP_SDK_UNAVAILABLE")

// Named image kinds are shared by the CGO and non-CGO runtime implementations.
const (
	imagePreview = iota
	imageLabel
	imageThumb
)

type parser struct {
	streamer streamer.Streamer
	reader   uintptr
	config   config
	header   types.HeaderInfo
	closeMu  sync.Mutex
}

type config struct {
	tileWidth, tileHeight      uint32
	imageWidth, imageHeight    uint32
	scanRatio, downsample, mpp float32
}

// New opens an HWP reader through the optional vendor SDK. The SDK is loaded
// at runtime and is never linked into the Go binary.
func New(fs streamer.Streamer) (types.ImageParser, error) {
	if fs == nil {
		return nil, errors.New("nil HWP streamer")
	}
	reader, err := runtimeOpen(fs.GetFileName())
	if err != nil {
		return nil, errUnavailable
	}
	cfg, err := runtimeConfig(reader)
	if err != nil {
		runtimeClose(reader)
		return nil, fmt.Errorf("HWP config: %w", err)
	}
	if cfg.imageWidth == 0 || cfg.imageHeight == 0 || cfg.tileWidth == 0 || cfg.tileHeight == 0 {
		runtimeClose(reader)
		return nil, fmt.Errorf("invalid HWP config dimensions %dx%d tile %dx%d", cfg.imageWidth, cfg.imageHeight, cfg.tileWidth, cfg.tileHeight)
	}
	levels := 0
	maxDim := math.Max(float64(cfg.imageWidth), float64(cfg.imageHeight))
	for maxDim > float64(cfg.tileWidth) && levels < 31 {
		maxDim /= 2
		levels++
	}
	if levels == 0 {
		levels = 1
	}
	fileSize, _ := fs.GetFileSize()
	p := &parser{streamer: fs, reader: reader, config: cfg}
	p.header = types.NewHeaderInfo(fs.GetFileName(), 0, levels-1, int(cfg.imageHeight), int(cfg.imageWidth), cfg.scanRatio, cfg.downsample, 0, 0, cfg.mpp, int(cfg.tileWidth))
	runtime.SetFinalizer(p, (*parser).finalize)
	_ = fileSize
	return p, nil
}

func (p *parser) finalize() { p.close() }

func (p *parser) close() {
	p.closeMu.Lock()
	defer p.closeMu.Unlock()
	if p.reader != 0 {
		runtimeClose(p.reader)
		p.reader = 0
	}
}

func (p *parser) GetFileName() string                          { return p.streamer.GetFileName() }
func (p *parser) GetFileSize() int64                           { n, _ := p.streamer.GetFileSize(); return n }
func (p *parser) GetDependencies() ([]string, error)           { return []string{p.GetFileName()}, nil }
func (p *parser) GetHeaderInfoFunc() (types.HeaderInfo, error) { return p.header, nil }

func (p *parser) GetImage(layer, line, row int, w io.Writer) error {
	if w == nil || layer < p.header.MinLayer || layer > p.header.MaxLayer || line < 0 || row < 0 {
		return errors.New("invalid HWP tile coordinate")
	}
	// The public HWP API accepts pixel origin and a floating-point scale. The
	// service presents layers from low to high resolution, so layer zero is the
	// coarsest level and the final layer is full resolution.
	shift := p.header.MaxLayer - layer
	scale := float32(uint32(1) << uint(shift))
	x := uint32(row) * p.config.tileWidth
	y := uint32(line) * p.config.tileHeight
	data, _, _, err := runtimeReadImage(p.reader, x, y, scale)
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
	data, _, _, err := runtimeReadNamed(p.reader, kind)
	if err != nil {
		return err
	}
	_, err = w.Write(data)
	return err
}
