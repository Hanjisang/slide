package service

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"imageparser/types"
)

func TestHealthAndFormats(t *testing.T) {
	server, err := NewServer(t.TempDir(), slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)))
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/health", "/api/formats"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("%s returned %d: %s", path, response.Code, response.Body.String())
		}
		var decoded any
		if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
			t.Fatalf("%s returned invalid JSON: %v", path, err)
		}
	}
}

func TestAnalyzeRejectsInvalidAndMissingSlideIDs(t *testing.T) {
	server, err := NewServer(t.TempDir(), slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)))
	if err != nil {
		t.Fatal(err)
	}
	for path, expected := range map[string]int{
		"/api/slides/not-a-number/analyze": http.StatusBadRequest,
		"/api/slides/99/analyze":           http.StatusNotFound,
	} {
		request := httptest.NewRequest(http.MethodPost, path, nil)
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != expected {
			t.Fatalf("%s returned %d, expected %d", path, response.Code, expected)
		}
	}
}

func TestNormalizeTilePreservesConfiguredTileSize(t *testing.T) {
	input := image.NewRGBA(image.Rect(0, 0, 32, 16))
	for y := 0; y < 16; y++ {
		for x := 0; x < 32; x++ {
			input.Set(x, y, color.RGBA{R: 200, A: 255})
		}
	}
	encoded := &bytes.Buffer{}
	if err := jpeg.Encode(encoded, input, nil); err != nil {
		t.Fatal(err)
	}
	for _, tileSize := range []int{256, 512} {
		result, err := normalizeImage(encoded.Bytes(), tileSize, true)
		if err != nil {
			t.Fatal(err)
		}
		decoded, err := jpeg.Decode(bytes.NewReader(result))
		if err != nil {
			t.Fatal(err)
		}
		if decoded.Bounds().Dx() != tileSize || decoded.Bounds().Dy() != tileSize {
			t.Fatalf("unexpected normalized dimensions for %d: %v", tileSize, decoded.Bounds())
		}
	}
}

func TestBrowserLevelsPreserveStoredPyramid(t *testing.T) {
	header := types.NewHeaderInfo("slide.hwp", 0, 1, 512, 1024, 20, 2, 0, 0, 0, 256)
	header.Levels = []types.PyramidLevel{
		{Width: 256, Height: 128, Downsample: 4, TileSize: 256},
		{Width: 1024, Height: 512, Downsample: 1, TileSize: 256},
	}
	levels := browserLevels(header)
	if len(levels) != 2 || levels[0]["width"] != 256 || levels[0]["downsample"] != float64(4) || levels[1]["width"] != 1024 {
		t.Fatalf("unexpected stored pyramid: %+v", levels)
	}
}
