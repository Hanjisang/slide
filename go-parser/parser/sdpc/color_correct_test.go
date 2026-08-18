package sdpc

import (
	"bytes"
	"image"
	"image/color"
	"io"
	"math"
	"testing"
)

func TestColorCorrectorReferencePixel(t *testing.T) {
	corrector, err := newColorCorrector(
		[3]float32{1, 1, 1},
		[3]float32{1, 1, 1},
		1,
		[3][3]float32{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
	)
	if err != nil {
		t.Fatal(err)
	}
	source := image.NewRGBA(image.Rect(0, 0, 1, 1))
	source.SetRGBA(0, 0, color.RGBA{R: 31, G: 127, B: 223, A: 10})
	result, err := corrector.Apply(source)
	if err != nil {
		t.Fatal(err)
	}
	got := result.RGBAAt(0, 0)
	if got.R != 31 || got.G != 130 || got.B != 223 || got.A != 255 {
		t.Fatalf("unexpected color correction result: %#v", got)
	}
}

func TestColorCorrectorRejectsInvalidInput(t *testing.T) {
	_, err := newColorCorrector([3]float32{}, [3]float32{}, float32(math.NaN()), [3][3]float32{})
	if err == nil {
		t.Fatal("expected invalid gamma to fail")
	}
	corrector, err := newColorCorrector([3]float32{1, 1, 1}, [3]float32{1, 1, 1}, 1, [3][3]float32{{1}, {0, 1}, {0, 0, 1}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := corrector.Apply(image.NewRGBA(image.Rect(0, 0, maxColorDimension+1, 1))); err == nil {
		t.Fatal("expected oversized image to fail")
	}
}

func TestThumbnailRejectsOversizedPayloadBeforeRead(t *testing.T) {
	sd := &Sdpc{}
	err := sd.ToColorCorrectRgba(0, maxEncodedImageSize+1, io.Discard)
	if err == nil {
		t.Fatal("expected oversized encoded image to fail")
	}
	if !bytes.Contains([]byte(err.Error()), []byte("size")) {
		t.Fatalf("unexpected error: %v", err)
	}
}
