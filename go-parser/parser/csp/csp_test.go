package csp

import (
	"bytes"
	"encoding/binary"
	"math"
	"os"
	"path/filepath"
	"testing"

	"imageparser/utils/streamer"
)

func TestParserReadsPyramidAndAssociatedImages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fixture.csp")
	pixelParts := [][]byte{[]byte("LABEL"), []byte("PREVIEW"), []byte("THUMB"), []byte("LOW_TILE"), []byte("HIGH_TILE")}
	offsets := make([]uint64, len(pixelParts))
	pixelData := []byte{}
	for i, part := range pixelParts {
		offsets[i] = uint64(len(pixelData))
		pixelData = append(pixelData, part...)
	}

	associated := [][]byte{
		fixtureSequence(uidAssociatedImage, fixtureLeaf(uidImageType, []byte{0}), fixtureLeaf(uidImageWidth, fixtureU32(32)), fixtureLeaf(uidImageHeight, fixtureU32(16)), fixtureLeaf(uidImageDataOffset, fixtureU64(offsets[0])), fixtureLeaf(uidImageDataLength, fixtureU64(uint64(len(pixelParts[0]))))),
		fixtureSequence(uidAssociatedImage, fixtureLeaf(uidImageType, []byte{1}), fixtureLeaf(uidImageWidth, fixtureU32(64)), fixtureLeaf(uidImageHeight, fixtureU32(32)), fixtureLeaf(uidImageDataOffset, fixtureU64(offsets[1])), fixtureLeaf(uidImageDataLength, fixtureU64(uint64(len(pixelParts[1]))))),
		fixtureSequence(uidAssociatedImage, fixtureLeaf(uidImageType, []byte{2}), fixtureLeaf(uidImageWidth, fixtureU32(16)), fixtureLeaf(uidImageHeight, fixtureU32(8)), fixtureLeaf(uidImageDataOffset, fixtureU64(offsets[2])), fixtureLeaf(uidImageDataLength, fixtureU64(uint64(len(pixelParts[2]))))),
	}
	low := fixtureFrame(256, 128, offsets[3], uint64(len(pixelParts[3])))
	high := fixtureFrame(512, 256, offsets[4], uint64(len(pixelParts[4])))
	scanConfig := fixtureSequence(uidScanConfiguration,
		fixtureLeaf(uidTileWidth, fixtureU32(256)), fixtureLeaf(uidTileHeight, fixtureU32(256)),
		fixtureLeaf(uidScanRatio, fixtureF32(40)), fixtureLeaf(uidScanMPP, fixtureF32(0.25)), fixtureLeaf(uidDownsampleRatio, fixtureF32(2)),
	)
	frames := fixtureSequence(uidMultiFrameInfo, high, low)
	image := fixtureSequence(uidImageInfo, frames)
	multiImage := fixtureSequence(uidMultiImageInfo, image)
	scan := fixtureSequence(uidMultiScanResult, fixtureSequence(uidScanResult, scanConfig, multiImage))

	file := fixtureHeader()
	for _, item := range associated {
		file = append(file, item...)
	}
	file = append(file, fixtureLeaf(uidPixelData, pixelData)...)
	file = append(file, scan...)
	if err := os.WriteFile(path, file, 0o600); err != nil {
		t.Fatal(err)
	}

	parsedValue, err := New(streamer.NewFile(path, binary.LittleEndian))
	if err != nil {
		t.Fatal(err)
	}
	parsed := parsedValue.(*Parser)
	header, _ := parsed.GetHeaderInfoFunc()
	if header.Width != 512 || header.Height != 256 || header.MinLayer != 0 || header.MaxLayer != 1 || header.BlockSize != 256 || header.Mpp != 0.25 {
		t.Fatalf("unexpected header: %+v", header)
	}
	for _, tc := range []struct {
		layer int
		want  string
	}{{0, "LOW_TILE"}, {1, "HIGH_TILE"}} {
		var output bytes.Buffer
		if err := parsed.GetImage(tc.layer, 0, 0, &output); err != nil {
			t.Fatal(err)
		}
		if output.String() != tc.want {
			t.Fatalf("layer %d returned %q", tc.layer, output.String())
		}
	}
	for _, tc := range []struct {
		read func(*bytes.Buffer) error
		want string
	}{
		{func(w *bytes.Buffer) error { return parsed.GetLabelInfoPathFunc(w) }, "LABEL"},
		{func(w *bytes.Buffer) error { return parsed.GetMacrograph(w) }, "PREVIEW"},
		{func(w *bytes.Buffer) error { return parsed.GetThumbnailImagePathFunc(w) }, "THUMB"},
	} {
		var output bytes.Buffer
		if err := tc.read(&output); err != nil {
			t.Fatal(err)
		}
		if output.String() != tc.want {
			t.Fatalf("associated image returned %q", output.String())
		}
	}
	if err := parsed.GetImage(1, 2, 0, &bytes.Buffer{}); err == nil {
		t.Fatal("out-of-bounds tile was accepted")
	}
}

func TestParserRejectsPixelRangeOverflow(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broken.csp")
	pixel := []byte("x")
	scanConfig := fixtureSequence(uidScanConfiguration, fixtureLeaf(uidTileWidth, fixtureU32(256)), fixtureLeaf(uidTileHeight, fixtureU32(256)))
	frame := fixtureFrame(256, 256, 100, 10)
	scan := fixtureSequence(uidMultiScanResult, fixtureSequence(uidScanResult, scanConfig, fixtureSequence(uidMultiImageInfo, fixtureSequence(uidImageInfo, fixtureSequence(uidMultiFrameInfo, frame)))))
	file := append(fixtureHeader(), fixtureLeaf(uidPixelData, pixel)...)
	file = append(file, scan...)
	if err := os.WriteFile(path, file, 0o600); err != nil {
		t.Fatal(err)
	}
	if parser, err := New(streamer.NewFile(path, binary.LittleEndian)); err == nil || parser != nil {
		t.Fatal("out-of-range CSP tile was accepted")
	}
}

func TestParserRejectsMalformedContainerBounds(t *testing.T) {
	declaredPastEOF := append(fixtureHeader(), fixtureEntry(uidPixelData, 1, 1, []byte("x"))[:22]...)
	binary.LittleEndian.PutUint64(declaredPastEOF[len(declaredPastEOF)-8:], 10)
	overflowLength := append(fixtureHeader(), fixtureEntry(uidPixelData, 1, 1, nil)...)
	binary.LittleEndian.PutUint64(overflowLength[len(overflowLength)-8:], math.MaxUint64)
	badSequenceCount := append(fixtureHeader(), fixtureEntry(uidMultiScanResult, dtSequence, math.MaxUint64, nil)...)
	for name, data := range map[string][]byte{
		"truncated header":      []byte("MEDIC"),
		"invalid value length":  declaredPastEOF,
		"overflow value length": overflowLength,
		"overflow child count":  badSequenceCount,
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "broken.csp")
			if err := os.WriteFile(path, data, 0o600); err != nil {
				t.Fatal(err)
			}
			if parser, err := New(streamer.NewFile(path, binary.LittleEndian)); err == nil || parser != nil {
				t.Fatalf("%s was accepted", name)
			}
		})
	}
}

func fixtureHeader() []byte {
	header := make([]byte, headerLength)
	copy(header, "MEDIC")
	binary.LittleEndian.PutUint16(header[12:14], 64)
	return header
}

func fixtureEntry(uid uint32, dt uint16, valueNum uint64, value []byte) []byte {
	result := make([]byte, 22, 22+len(value))
	binary.LittleEndian.PutUint16(result[0:2], uint16(uid>>16))
	binary.LittleEndian.PutUint16(result[2:4], uint16(uid))
	binary.LittleEndian.PutUint16(result[4:6], dt)
	binary.LittleEndian.PutUint64(result[6:14], valueNum)
	binary.LittleEndian.PutUint64(result[14:22], uint64(len(value)))
	return append(result, value...)
}

func fixtureLeaf(uid uint32, value []byte) []byte { return fixtureEntry(uid, 1, 1, value) }
func fixtureSequence(uid uint32, children ...[]byte) []byte {
	value := []byte{}
	for _, child := range children {
		value = append(value, child...)
	}
	return fixtureEntry(uid, dtSequence, uint64(len(children)), value)
}
func fixtureU32(value uint32) []byte {
	out := make([]byte, 4)
	binary.LittleEndian.PutUint32(out, value)
	return out
}
func fixtureU64(value uint64) []byte {
	out := make([]byte, 8)
	binary.LittleEndian.PutUint64(out, value)
	return out
}
func fixtureF32(value float32) []byte { return fixtureU32(math.Float32bits(value)) }
func fixtureFrame(width, height uint32, offset, length uint64) []byte {
	tileValue := make([]byte, 36)
	binary.LittleEndian.PutUint32(tileValue[0:4], min(width, 256))
	binary.LittleEndian.PutUint32(tileValue[4:8], min(height, 256))
	binary.LittleEndian.PutUint64(tileValue[8:16], offset)
	binary.LittleEndian.PutUint64(tileValue[16:24], length)
	return fixtureSequence(uidFrameInfo,
		fixtureLeaf(uidFrameWidth, fixtureU32(width)), fixtureLeaf(uidFrameHeight, fixtureU32(height)), fixtureLeaf(uidFrameRatio, fixtureF32(1)),
		fixtureSequence(uidMultiTileInfo, fixtureLeaf(uidTileInfo, tileValue)),
	)
}
