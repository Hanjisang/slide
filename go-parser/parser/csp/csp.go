// Package csp implements the public OpenCsp container format in pure Go.
// It intentionally reads metadata and image ranges through streamer.Streamer
// instead of loading whole-slide files into memory.
package csp

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"

	"imageparser/types"
	"imageparser/utils/streamer"
)

const (
	headerLength     int64  = 128
	dtSequence       uint16 = 0x000e
	maxEntries              = 2_000_000
	maxTiles                = 1_500_000
	maxLevels               = 32
	maxSequenceDepth        = 16
	maxMetadataValue uint64 = 1 << 20
	maxImageBytes    uint64 = 64 << 20

	uidAssociatedImage   uint32 = 0x00020001
	uidImageType         uint32 = 0x00020002
	uidImageWidth        uint32 = 0x00020003
	uidImageHeight       uint32 = 0x00020004
	uidImageDataOffset   uint32 = 0x00020005
	uidImageDataLength   uint32 = 0x00020006
	uidMultiImageInfo    uint32 = 0x00020009
	uidImageInfo         uint32 = 0x0002000a
	uidMultiFrameInfo    uint32 = 0x0002001e
	uidFrameInfo         uint32 = 0x0002001f
	uidFrameRatio        uint32 = 0x00020021
	uidFrameWidth        uint32 = 0x00020022
	uidFrameHeight       uint32 = 0x00020023
	uidMultiTileInfo     uint32 = 0x00020024
	uidTileInfo          uint32 = 0x00020025
	uidPixelData         uint32 = 0x00030001
	uidScanConfiguration uint32 = 0x00040001
	uidScanTime          uint32 = 0x00040003
	uidTileWidth         uint32 = 0x00040007
	uidTileHeight        uint32 = 0x00040008
	uidScanRatio         uint32 = 0x00040009
	uidScanMPP           uint32 = 0x0004000a
	uidMultiScanResult   uint32 = 0x00050001
	uidScanResult        uint32 = 0x00050002
	uidDownsampleRatio   uint32 = 0x00060002
)

type entry struct {
	uid         uint32
	dt          uint16
	valueNum    uint64
	valueLength uint64
	valueOffset int64
}

type tile struct {
	offset uint64
	length uint64
}

type frame struct {
	width  uint32
	height uint32
	ratio  float32
	tiles  map[uint64]tile
}

type associatedImage struct {
	kind   uint8
	width  uint32
	height uint32
	offset uint64
	length uint64
	valid  bool
}

type Parser struct {
	stream      streamer.Streamer
	fileSize    int64
	headerSize  int64
	pixelOffset int64
	pixelLength uint64
	tileWidth   uint32
	tileHeight  uint32
	scanRatio   float32
	mpp         float32
	downsample  float32
	frames      []frame // public order: lowest to highest resolution
	associated  [3]associatedImage
	header      types.HeaderInfo
	entryCount  int
	tileCount   int
}

func New(s streamer.Streamer) (types.ImageParser, error) {
	if s == nil {
		return nil, errors.New("nil CSP streamer")
	}
	fileSize, err := s.GetFileSize()
	if err != nil {
		return nil, err
	}
	if fileSize < headerLength {
		return nil, errors.New("invalid CSP: file is smaller than 128-byte header")
	}
	header := make([]byte, headerLength)
	if err := s.Range2Type(0, headerLength, &header); err != nil {
		return nil, fmt.Errorf("read CSP header: %w", err)
	}
	if string(header[:5]) != "MEDIC" {
		return nil, errors.New("invalid CSP signature: missing MEDIC")
	}
	offsetType := binary.LittleEndian.Uint16(header[12:14])
	var variableWidth int64
	switch offsetType {
	case 16:
		variableWidth = 2
	case 32:
		variableWidth = 4
	case 64:
		variableWidth = 8
	default:
		return nil, fmt.Errorf("unsupported CSP offset type %d", offsetType)
	}
	p := &Parser{stream: s, fileSize: fileSize, headerSize: 6 + 2*variableWidth, pixelOffset: -1}
	if err := p.parseTopLevel(); err != nil {
		return nil, err
	}
	if err := p.finish(); err != nil {
		return nil, err
	}
	return p, nil
}

func (p *Parser) parseTopLevel() error {
	for offset := headerLength; offset < p.fileSize; {
		e, next, err := p.readEntry(offset, p.fileSize)
		if err != nil {
			return fmt.Errorf("CSP top-level entry at %d: %w", offset, err)
		}
		switch e.uid {
		case uidPixelData:
			if p.pixelOffset >= 0 {
				return errors.New("invalid CSP: duplicate pixel data entry")
			}
			p.pixelOffset, p.pixelLength = e.valueOffset, e.valueLength
		case uidAssociatedImage:
			if err := p.parseAssociated(e); err != nil {
				return err
			}
		case uidMultiScanResult:
			if err := p.parseMultiScan(e); err != nil {
				return err
			}
		}
		if next <= offset {
			return errors.New("invalid CSP: entry made no forward progress")
		}
		offset = next
	}
	return nil
}

func (p *Parser) readEntry(offset, limit int64) (entry, int64, error) {
	p.entryCount++
	if p.entryCount > maxEntries {
		return entry{}, 0, errors.New("metadata entry count exceeds safety limit")
	}
	if offset < 0 || limit < offset || p.headerSize > limit-offset {
		return entry{}, 0, errors.New("entry header exceeds enclosing range")
	}
	raw := make([]byte, p.headerSize)
	if err := p.stream.Range2Type(offset, p.headerSize, &raw); err != nil {
		return entry{}, 0, err
	}
	e := entry{uid: uint32(binary.LittleEndian.Uint16(raw[0:2]))<<16 | uint32(binary.LittleEndian.Uint16(raw[2:4])), dt: binary.LittleEndian.Uint16(raw[4:6])}
	switch p.headerSize {
	case 10:
		e.valueNum = uint64(binary.LittleEndian.Uint16(raw[6:8]))
		e.valueLength = uint64(binary.LittleEndian.Uint16(raw[8:10]))
	case 14:
		e.valueNum = uint64(binary.LittleEndian.Uint32(raw[6:10]))
		e.valueLength = uint64(binary.LittleEndian.Uint32(raw[10:14]))
	case 22:
		e.valueNum = binary.LittleEndian.Uint64(raw[6:14])
		e.valueLength = binary.LittleEndian.Uint64(raw[14:22])
	default:
		return entry{}, 0, errors.New("invalid CSP entry header size")
	}
	e.valueOffset = offset + p.headerSize
	if e.valueLength > uint64(limit-e.valueOffset) {
		return entry{}, 0, errors.New("entry value exceeds enclosing range")
	}
	if e.dt == dtSequence && e.valueNum > maxEntries {
		return entry{}, 0, errors.New("sequence child count exceeds safety limit")
	}
	if e.dt == dtSequence && e.valueNum > 0 && uint64(p.headerSize) > e.valueLength/e.valueNum {
		return entry{}, 0, errors.New("sequence child headers exceed declared value length")
	}
	return e, e.valueOffset + int64(e.valueLength), nil
}

func (p *Parser) eachChild(parent entry, depth int, fn func(entry) error) error {
	if depth < 1 || depth > maxSequenceDepth {
		return errors.New("CSP sequence depth exceeds safety limit")
	}
	if parent.dt != dtSequence {
		return fmt.Errorf("UID %#08x is not a sequence", parent.uid)
	}
	limit := parent.valueOffset + int64(parent.valueLength)
	offset := parent.valueOffset
	for i := uint64(0); i < parent.valueNum; i++ {
		child, next, err := p.readEntry(offset, limit)
		if err != nil {
			return err
		}
		if err := fn(child); err != nil {
			return err
		}
		offset = next
	}
	if offset != limit {
		return errors.New("sequence value has leftover bytes")
	}
	return nil
}

func (p *Parser) parseAssociated(parent entry) error {
	image := associatedImage{}
	err := p.eachChild(parent, 1, func(child entry) error {
		switch child.uid {
		case uidImageType:
			value, err := p.readValue(child, 1)
			if err != nil {
				return err
			}
			if value[0] > 2 {
				return errors.New("associated image type is out of range")
			}
			image.kind, image.valid = value[0], true
		case uidImageWidth:
			v, err := p.readUint32(child)
			if err != nil {
				return err
			}
			image.width = v
		case uidImageHeight:
			v, err := p.readUint32(child)
			if err != nil {
				return err
			}
			image.height = v
		case uidImageDataOffset:
			v, err := p.readUint64(child)
			if err != nil {
				return err
			}
			image.offset = v
		case uidImageDataLength:
			v, err := p.readUint64(child)
			if err != nil {
				return err
			}
			image.length = v
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("parse CSP associated image: %w", err)
	}
	if image.valid {
		p.associated[image.kind] = image
	}
	return nil
}

func (p *Parser) parseMultiScan(parent entry) error {
	return p.eachChild(parent, 1, func(child entry) error {
		if child.uid == uidScanResult {
			return p.parseScanResult(child, 2)
		}
		return nil
	})
}

func (p *Parser) parseScanResult(parent entry, depth int) error {
	return p.eachChild(parent, depth, func(child entry) error {
		switch child.uid {
		case uidScanConfiguration:
			return p.parseScanConfig(child, depth+1)
		case uidMultiImageInfo:
			if len(p.frames) == 0 {
				return p.parseMultiImages(child, depth+1)
			}
		}
		return nil
	})
}

func (p *Parser) parseScanConfig(parent entry, depth int) error {
	err := p.eachChild(parent, depth, func(child entry) error {
		switch child.uid {
		case uidScanTime:
			if child.valueLength > maxMetadataValue {
				return errors.New("scan time value exceeds safety limit")
			}
		case uidTileWidth:
			v, err := p.readUint32(child)
			if err != nil {
				return err
			}
			p.tileWidth = v
		case uidTileHeight:
			v, err := p.readUint32(child)
			if err != nil {
				return err
			}
			p.tileHeight = v
		case uidScanRatio:
			v, err := p.readFloat32(child)
			if err != nil {
				return err
			}
			p.scanRatio = v
		case uidScanMPP:
			v, err := p.readFloat32(child)
			if err != nil {
				return err
			}
			p.mpp = v
		case uidDownsampleRatio:
			v, err := p.readFloat32(child)
			if err != nil {
				return err
			}
			p.downsample = v
		}
		return nil
	})
	if err != nil {
		return err
	}
	if p.tileWidth == 0 || p.tileHeight == 0 || p.tileWidth > 4096 || p.tileHeight > 4096 {
		return fmt.Errorf("invalid CSP tile size %dx%d", p.tileWidth, p.tileHeight)
	}
	return nil
}

func (p *Parser) parseMultiImages(parent entry, depth int) error {
	found := false
	err := p.eachChild(parent, depth, func(child entry) error {
		if child.uid != uidImageInfo || found {
			return nil
		}
		found = true
		return p.parseImage(child, depth+1)
	})
	if err != nil {
		return err
	}
	if !found {
		return errors.New("no CSP image info found")
	}
	return nil
}

func (p *Parser) parseImage(parent entry, depth int) error {
	return p.eachChild(parent, depth, func(child entry) error {
		if child.uid == uidMultiFrameInfo {
			return p.parseFrames(child, depth+1)
		}
		return nil
	})
}

func (p *Parser) parseFrames(parent entry, depth int) error {
	native := make([]frame, 0, min(int(parent.valueNum), maxLevels))
	err := p.eachChild(parent, depth, func(child entry) error {
		if child.uid != uidFrameInfo {
			return nil
		}
		if len(native) >= maxLevels {
			return errors.New("CSP level count exceeds safety limit")
		}
		parsed, err := p.parseFrame(child, depth+1)
		if err != nil {
			return err
		}
		native = append(native, parsed)
		return nil
	})
	if err != nil {
		return err
	}
	if len(native) == 0 {
		return errors.New("CSP image has no frames")
	}
	p.frames = make([]frame, len(native))
	for i := range native {
		p.frames[len(native)-1-i] = native[i]
	}
	return nil
}

func (p *Parser) parseFrame(parent entry, depth int) (frame, error) {
	f := frame{tiles: make(map[uint64]tile)}
	err := p.eachChild(parent, depth, func(child entry) error {
		switch child.uid {
		case uidFrameWidth:
			v, err := p.readUint32(child)
			if err != nil {
				return err
			}
			f.width = v
		case uidFrameHeight:
			v, err := p.readUint32(child)
			if err != nil {
				return err
			}
			f.height = v
		case uidFrameRatio:
			v, err := p.readFloat32(child)
			if err != nil {
				return err
			}
			f.ratio = v
		case uidMultiTileInfo:
			return p.parseTiles(child, &f, depth+1)
		}
		return nil
	})
	if err != nil {
		return frame{}, err
	}
	if f.width == 0 || f.height == 0 || f.width > 2_000_000 || f.height > 2_000_000 {
		return frame{}, fmt.Errorf("invalid CSP frame dimensions %dx%d", f.width, f.height)
	}
	if len(f.tiles) == 0 {
		return frame{}, errors.New("CSP frame has no tiles")
	}
	return f, nil
}

func (p *Parser) parseTiles(parent entry, f *frame, depth int) error {
	return p.eachChild(parent, depth, func(child entry) error {
		if child.uid != uidTileInfo {
			return nil
		}
		p.tileCount++
		if p.tileCount > maxTiles {
			return errors.New("CSP tile count exceeds safety limit")
		}
		value, err := p.readValue(child, 36)
		if err != nil {
			return err
		}
		width := binary.LittleEndian.Uint32(value[0:4])
		height := binary.LittleEndian.Uint32(value[4:8])
		offset := binary.LittleEndian.Uint64(value[8:16])
		length := binary.LittleEndian.Uint64(value[16:24])
		x := binary.LittleEndian.Uint32(value[24:28])
		y := binary.LittleEndian.Uint32(value[28:32])
		if width == 0 || height == 0 || width > 4096 || height > 4096 || length == 0 || length > maxImageBytes {
			return errors.New("invalid CSP tile dimensions or data length")
		}
		if p.tileWidth == 0 || p.tileHeight == 0 {
			return errors.New("CSP tile index encountered before scan configuration")
		}
		key := tileKey(x/p.tileWidth, y/p.tileHeight)
		if _, duplicate := f.tiles[key]; duplicate {
			return errors.New("duplicate CSP tile coordinate")
		}
		f.tiles[key] = tile{offset: offset, length: length}
		return nil
	})
}

func (p *Parser) readValue(e entry, minimum int) ([]byte, error) {
	if e.valueLength < uint64(minimum) || e.valueLength > maxMetadataValue {
		return nil, fmt.Errorf("UID %#08x has invalid value length %d", e.uid, e.valueLength)
	}
	value := make([]byte, int(e.valueLength))
	if err := p.stream.Range2Type(e.valueOffset, int64(e.valueLength), &value); err != nil {
		return nil, err
	}
	return value, nil
}

func (p *Parser) readUint32(e entry) (uint32, error) {
	v, err := p.readValue(e, 4)
	if err != nil {
		return 0, err
	}
	return binary.LittleEndian.Uint32(v[:4]), nil
}

func (p *Parser) readUint64(e entry) (uint64, error) {
	v, err := p.readValue(e, 8)
	if err != nil {
		return 0, err
	}
	return binary.LittleEndian.Uint64(v[:8]), nil
}

func (p *Parser) readFloat32(e entry) (float32, error) {
	v, err := p.readUint32(e)
	if err != nil {
		return 0, err
	}
	value := math.Float32frombits(v)
	if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
		return 0, errors.New("CSP metadata contains non-finite float")
	}
	return value, nil
}

func (p *Parser) finish() error {
	if p.pixelOffset < 0 || p.pixelLength == 0 {
		return errors.New("CSP pixel data entry is missing or empty")
	}
	if len(p.frames) == 0 {
		return errors.New("CSP pyramid metadata is missing")
	}
	if p.tileWidth == 0 || p.tileHeight == 0 {
		return errors.New("CSP scan configuration is missing")
	}
	for _, f := range p.frames {
		for _, t := range f.tiles {
			if t.offset > p.pixelLength || t.length > p.pixelLength-t.offset {
				return errors.New("CSP tile range exceeds pixel data")
			}
		}
	}
	for _, image := range p.associated {
		if image.valid && (image.length == 0 || image.length > maxImageBytes || image.offset > p.pixelLength || image.length > p.pixelLength-image.offset) {
			return errors.New("CSP associated image range exceeds pixel data")
		}
	}
	highest := p.frames[len(p.frames)-1]
	blockSize := max(int(p.tileWidth), int(p.tileHeight))
	p.header = types.NewHeaderInfo(p.stream.GetFileName(), 0, len(p.frames)-1, int(highest.height), int(highest.width), p.scanRatio, p.downsample, 0, 0, p.mpp, blockSize)
	return nil
}

func tileKey(col, row uint32) uint64 { return uint64(row)<<32 | uint64(col) }

func (p *Parser) GetFileName() string                          { return p.stream.GetFileName() }
func (p *Parser) GetFileSize() int64                           { return p.fileSize }
func (p *Parser) GetDependencies() ([]string, error)           { return []string{p.GetFileName()}, nil }
func (p *Parser) GetHeaderInfoFunc() (types.HeaderInfo, error) { return p.header, nil }

func (p *Parser) GetImage(layer, line, row int, w io.Writer) error {
	if w == nil || layer < 0 || layer >= len(p.frames) || line < 0 || row < 0 {
		return errors.New("invalid CSP tile coordinate")
	}
	f := p.frames[layer]
	cols := (uint64(f.width) + uint64(p.tileWidth) - 1) / uint64(p.tileWidth)
	rows := (uint64(f.height) + uint64(p.tileHeight) - 1) / uint64(p.tileHeight)
	if uint64(line) >= cols || uint64(row) >= rows {
		return errors.New("CSP tile coordinate is outside frame bounds")
	}
	t, ok := f.tiles[tileKey(uint32(line), uint32(row))]
	if !ok {
		return errors.New("CSP tile is not present")
	}
	return p.stream.Range2Writer(p.pixelOffset+int64(t.offset), int64(t.length), w)
}

func (p *Parser) GetThumbnailImagePathFunc(w io.Writer) error { return p.writeAssociated(2, w) }
func (p *Parser) GetLabelInfoPathFunc(w io.Writer) error      { return p.writeAssociated(0, w) }
func (p *Parser) GetMacrograph(w io.Writer) error             { return p.writeAssociated(1, w) }

func (p *Parser) writeAssociated(kind int, w io.Writer) error {
	if w == nil {
		return errors.New("nil CSP image writer")
	}
	image := p.associated[kind]
	if !image.valid {
		return errors.New("CSP associated image is not available")
	}
	return p.stream.Range2Writer(p.pixelOffset+int64(image.offset), int64(image.length), w)
}
