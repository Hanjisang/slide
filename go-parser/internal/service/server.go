package service

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	_ "image/png"
	"io"
	"log/slog"
	"math"
	"net/http"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/nfnt/resize"

	"imageparser/internal/registry"
)

const maxImageOutput = 64 << 20

type Server struct {
	registry *registry.Registry
	cache    *ParserCache
	logger   *slog.Logger
	mux      *http.ServeMux
}

func NewServer(root string, logger *slog.Logger) (*Server, error) {
	parserRegistry := registry.New()
	cache, err := NewParserCache(root, 30*time.Minute, 128, parserRegistry)
	if err != nil {
		return nil, err
	}
	s := &Server{registry: parserRegistry, cache: cache, logger: logger, mux: http.NewServeMux()}
	s.routes()
	return s, nil
}

func (s *Server) Handler() http.Handler { return s.recover(s.mux) }

func (s *Server) routes() {
	s.mux.HandleFunc("GET /health", s.health)
	s.mux.HandleFunc("GET /api/formats", s.formats)
	s.mux.HandleFunc("POST /api/slides/{id}/analyze", s.analyze)
	s.mux.HandleFunc("GET /api/slides/{id}/thumbnail", s.image("thumbnail"))
	s.mux.HandleFunc("GET /api/slides/{id}/label", s.image("label"))
	s.mux.HandleFunc("GET /api/slides/{id}/macro", s.image("macro"))
	s.mux.HandleFunc("GET /api/slides/{id}/tiles/{level}/{x}/{y}", s.tile)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	formats := s.registry.Formats()
	parserCount := 0
	for _, item := range formats {
		if item.Build {
			parserCount++
		}
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "UP", "parserCount": parserCount, "cgo": registry.CGOEnabled()})
}

func (s *Server) formats(w http.ResponseWriter, _ *http.Request) {
	s.writeJSON(w, http.StatusOK, s.registry.Formats())
}

func (s *Server) analyze(w http.ResponseWriter, r *http.Request) {
	slideID, err := pathInt(r, "id")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, err)
		return
	}
	entry, err := s.cache.Get(slideID)
	if err != nil {
		s.writeError(w, parserErrorStatus(err), err)
		return
	}
	entry.mu.Lock()
	defer entry.mu.Unlock()
	header := entry.header
	levels := make([]map[string]any, 0, header.MaxLayer-header.MinLayer+1)
	for browserLevel := 0; browserLevel <= header.MaxLayer-header.MinLayer; browserLevel++ {
		downsample := math.Pow(2, float64(header.MaxLayer-header.MinLayer-browserLevel))
		levels = append(levels, map[string]any{
			"level": browserLevel, "width": max(1, int(math.Ceil(float64(header.Width)/downsample))),
			"height": max(1, int(math.Ceil(float64(header.Height)/downsample))), "downsample": downsample,
		})
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"status": "READY", "format": entry.capability.Format, "adapterType": "GO_PARSER",
		"sdkStatus": "AVAILABLE", "width": header.Width, "height": header.Height,
		"levelCount": len(levels), "levels": levels,
		"properties": map[string]any{"engine": entry.capability.Engine, "sourceStatus": entry.capability.Status, "mpp": header.Mpp},
	})
}

func (s *Server) image(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		slideID, err := pathInt(r, "id")
		if err != nil {
			s.writeError(w, http.StatusBadRequest, err)
			return
		}
		entry, err := s.cache.Get(slideID)
		if err != nil {
			s.writeError(w, parserErrorStatus(err), err)
			return
		}
		entry.mu.Lock()
		defer entry.mu.Unlock()
		buffer := &limitedBuffer{limit: maxImageOutput}
		switch kind {
		case "thumbnail":
			err = entry.parser.GetThumbnailImagePathFunc(buffer)
		case "label":
			err = entry.parser.GetLabelInfoPathFunc(buffer)
		case "macro":
			err = entry.parser.GetMacrograph(buffer)
		}
		if err != nil || buffer.Len() == 0 {
			if err == nil {
				err = errors.New("NOT_AVAILABLE")
			}
			s.writeError(w, http.StatusNotFound, err)
			return
		}
		data, err := normalizeImage(buffer.Bytes(), 512, false)
		if err != nil {
			s.writeError(w, http.StatusUnprocessableEntity, err)
			return
		}
		writeJPEG(w, data)
	}
}

func (s *Server) tile(w http.ResponseWriter, r *http.Request) {
	slideID, err := pathInt(r, "id")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, err)
		return
	}
	level, err := pathInt(r, "level")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, err)
		return
	}
	x, err := pathInt(r, "x")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, err)
		return
	}
	y, err := pathInt(r, "y")
	if err != nil {
		s.writeError(w, http.StatusBadRequest, err)
		return
	}
	if level > 31 || x > 1_000_000 || y > 1_000_000 {
		s.writeError(w, http.StatusBadRequest, errors.New("tile coordinate exceeds safety limit"))
		return
	}
	entry, err := s.cache.Get(slideID)
	if err != nil {
		s.writeError(w, parserErrorStatus(err), err)
		return
	}
	if level > int64(entry.header.MaxLayer-entry.header.MinLayer) {
		s.writeError(w, http.StatusBadRequest, errors.New("INVALID_LEVEL"))
		return
	}
	entry.mu.Lock()
	defer entry.mu.Unlock()
	buffer := &limitedBuffer{limit: maxImageOutput}
	err = entry.parser.GetImage(int(level)+entry.header.MinLayer, int(x), int(y), buffer)
	if err != nil {
		s.writeError(w, http.StatusUnprocessableEntity, err)
		return
	}
	data, err := normalizeImage(buffer.Bytes(), 256, true)
	if err != nil {
		s.writeError(w, http.StatusUnprocessableEntity, err)
		return
	}
	writeJPEG(w, data)
}

func normalizeImage(data []byte, maxSize int, square bool) ([]byte, error) {
	if len(data) == 0 || len(data) > maxImageOutput {
		return nil, errors.New("invalid image output size")
	}
	decoded, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("parser returned undecodable image: %w", err)
	}
	if square {
		if decoded.Bounds().Dx() > maxSize || decoded.Bounds().Dy() > maxSize {
			decoded = resize.Resize(uint(maxSize), uint(maxSize), decoded, resize.Lanczos3)
		}
		canvas := image.NewRGBA(image.Rect(0, 0, maxSize, maxSize))
		draw.Draw(canvas, canvas.Bounds(), &image.Uniform{C: color.White}, image.Point{}, draw.Src)
		draw.Draw(canvas, image.Rect(0, 0, min(maxSize, decoded.Bounds().Dx()), min(maxSize, decoded.Bounds().Dy())), decoded, decoded.Bounds().Min, draw.Src)
		decoded = canvas
	} else if decoded.Bounds().Dx() > maxSize || decoded.Bounds().Dy() > maxSize {
		decoded = resize.Thumbnail(uint(maxSize), uint(maxSize), decoded, resize.Lanczos3)
	}
	output := &bytes.Buffer{}
	if err := jpeg.Encode(output, decoded, &jpeg.Options{Quality: 88}); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

func pathInt(r *http.Request, name string) (int64, error) {
	value, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil || value < 0 {
		return 0, fmt.Errorf("invalid %s", name)
	}
	return value, nil
}

func parserErrorStatus(err error) int {
	message := err.Error()
	if message == "SLIDE_NOT_CACHED" {
		return http.StatusNotFound
	}
	if message == "UNSUPPORTED_FORMAT" {
		return http.StatusUnsupportedMediaType
	}
	if len(message) >= len("SDK_REQUIRED") && (message[:len("SDK_REQUIRED")] == "SDK_REQUIRED" || message[:len("SDK_BUNDLED")] == "SDK_BUNDLED") {
		return http.StatusUnprocessableEntity
	}
	return http.StatusUnprocessableEntity
}

func (s *Server) writeError(w http.ResponseWriter, status int, err error) {
	s.writeJSON(w, status, map[string]any{"status": "FAILED", "error": err.Error()})
}

func (s *Server) writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeJPEG(w http.ResponseWriter, data []byte) {
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (s *Server) recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				s.logger.Error("parser request panic", "path", r.URL.Path, "panic", recovered, "stack", string(debug.Stack()))
				s.writeError(w, http.StatusInternalServerError, fmt.Errorf("parser panic: %v", recovered))
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type limitedBuffer struct {
	bytes.Buffer
	limit int
}

func (b *limitedBuffer) Write(data []byte) (int, error) {
	if len(data) > b.limit-b.Len() {
		return 0, errors.New("parser image output exceeds safety limit")
	}
	return b.Buffer.Write(data)
}

var _ io.Writer = (*limitedBuffer)(nil)
