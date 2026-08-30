package hwp

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"math"
	"runtime"
	"sort"
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
	levels                     []hwpLevel
}

type hwpLevel struct {
	width, height    uint32
	originX, originY uint32
	ratio            float32
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
	var magic [8]byte
	if s == nil {
		return nil, errors.New("invalid HWP parser dependency")
	}
	if err := s.Range2Type(0, int64(len(magic)), &magic); err != nil {
		return nil, fmt.Errorf("HWP header: %w", err)
	}
	if !bytes.Equal(magic[:], []byte("HW_MEDIC")) && !bytes.Equal(magic[:5], []byte("MEDIC")) {
		return nil, fmt.Errorf("HWP_UNSUPPORTED_MAGIC: got %q", magic)
	}
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
	if len(cfg.levels) == 0 {
		maxLayer := 0
		maxDimension := max(uint64(cfg.imageWidth), uint64(cfg.imageHeight))
		minimumTile := min(uint64(cfg.tileWidth), uint64(cfg.tileHeight))
		for maxDimension > minimumTile && maxLayer < 31 {
			maxDimension = (maxDimension + 1) / 2
			maxLayer++
		}
		for layer := 0; layer <= maxLayer; layer++ {
			shift := maxLayer - layer
			ratio := uint64(1) << uint(shift)
			cfg.levels = append(cfg.levels, hwpLevel{
				width:  uint32((uint64(cfg.imageWidth) + ratio - 1) / ratio),
				height: uint32((uint64(cfg.imageHeight) + ratio - 1) / ratio),
				ratio:  float32(ratio),
			})
		}
	}
	sort.Slice(cfg.levels, func(i, j int) bool {
		if cfg.levels[i].width == cfg.levels[j].width {
			return cfg.levels[i].height < cfg.levels[j].height
		}
		return cfg.levels[i].width < cfg.levels[j].width
	})
	if len(cfg.levels) > 32 {
		native.close(reader)
		return nil, fmt.Errorf("invalid HWP frame count: %d", len(cfg.levels))
	}
	maxLayer := len(cfg.levels) - 1
	p := &parser{stream: s, native: native, reader: reader, config: cfg}
	p.header = types.NewHeaderInfo(s.GetFileName(), 0, maxLayer, int(cfg.imageHeight), int(cfg.imageWidth), cfg.scanRatio, cfg.downsample, 0, 0, cfg.mpp, max(int(cfg.tileWidth), int(cfg.tileHeight)))
	for _, level := range cfg.levels {
		downsample := float64(cfg.imageWidth) / float64(level.width)
		if cfg.scanRatio > 0 && level.ratio > 0 {
			downsample = float64(cfg.scanRatio / level.ratio)
		}
		p.header.Levels = append(p.header.Levels, types.PyramidLevel{
			Width: int(level.width), Height: int(level.height), Downsample: downsample,
			TileSize: max(int(cfg.tileWidth), int(cfg.tileHeight)),
		})
	}
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
	level := p.config.levels[layer-p.header.MinLayer]
	levelWidth := uint64(level.width)
	levelHeight := uint64(level.height)
	cols := (levelWidth + uint64(p.config.tileWidth) - 1) / uint64(p.config.tileWidth)
	rows := (levelHeight + uint64(p.config.tileHeight) - 1) / uint64(p.config.tileHeight)
	if uint64(line) >= cols || uint64(row) >= rows {
		return errors.New("HWP tile coordinate is outside level bounds")
	}
	x := uint64(level.originX) + uint64(line)*uint64(p.config.tileWidth)
	y := uint64(level.originY) + uint64(row)*uint64(p.config.tileHeight)
	if x > math.MaxUint32 || y > math.MaxUint32 {
		return errors.New("HWP tile origin overflows SDK coordinate")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.reader == 0 {
		return errors.New("HWP reader is closed")
	}
	data, _, _, err := p.native.readImage(p.reader, uint32(x), uint32(y), level.ratio)
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
