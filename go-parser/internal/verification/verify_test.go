package verification

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"testing"
)

func TestPlannedCoordinatesUseRealLevelBounds(t *testing.T) {
	metadata := Metadata{LevelCount: 3, Levels: []Level{
		{Level: 0, Cols: 1, Rows: 1},
		{Level: 1, Cols: 4, Rows: 3},
		{Level: 2, Cols: 9, Rows: 7},
	}}
	coordinates := plannedCoordinates(metadata)
	if len(coordinates) < 6 {
		t.Fatalf("expected low/middle/high/edge coordinates, got %d", len(coordinates))
	}
	for _, coordinate := range coordinates {
		level := metadata.Levels[coordinate.Level]
		if coordinate.X < 0 || coordinate.X >= level.Cols || coordinate.Y < 0 || coordinate.Y >= level.Rows {
			t.Fatalf("coordinate out of bounds: %+v for %+v", coordinate, level)
		}
	}
}

func TestInspectImageRejectsEmptyAndDecodesPNG(t *testing.T) {
	if result := inspectImage(nil); result.Status != "FAILED" {
		t.Fatalf("expected empty image failure, got %+v", result)
	}
	input := image.NewRGBA(image.Rect(0, 0, 8, 6))
	for y := 0; y < 6; y++ {
		for x := 0; x < 8; x++ {
			input.Set(x, y, color.RGBA{R: uint8(x * 20), G: uint8(y * 30), B: 80, A: 255})
		}
	}
	var buffer bytes.Buffer
	if err := png.Encode(&buffer, input); err != nil {
		t.Fatal(err)
	}
	result := inspectImage(buffer.Bytes())
	if result.Status != "PASS" || result.Width != 8 || result.Height != 6 {
		t.Fatalf("unexpected image result: %+v", result)
	}
}

func TestSanitizeRemovesPathAndBaseName(t *testing.T) {
	item := InventoryItem{Alias: "KFB_SAMPLE_01", Path: `/samples/patient-123.kfb`}
	message := sanitize(`open /samples/patient-123.kfb: patient-123.kfb failed`, item)
	if message != `open KFB_SAMPLE_01: KFB_SAMPLE_01 failed` {
		t.Fatalf("unexpected sanitized error: %s", message)
	}
}
