package verification

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"math"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "golang.org/x/image/bmp"
	_ "golang.org/x/image/tiff"

	"imageparser/internal/registry"
	"imageparser/types"
)

var targetFormats = map[string]string{
	".svs": "SVS", ".kfb": "KFB", ".tmap": "TMAP", ".mdsx": "MDSX",
	".dmetrix": "DMETRIX", ".fenlan": "FENLAN", ".zyp": "ZYP", ".sdpc": "SDPC",
	".hwp": "HWP", ".tron": "TRON", ".csp": "CSP",
}

type Options struct {
	RandomTiles int
	Performance int
	Stability   int
	Concurrency []int
	Seed        int64
}

func DefaultOptions() Options {
	return Options{RandomTiles: 20, Performance: 10, Stability: 100, Concurrency: []int{5, 10}, Seed: 36}
}

type InventoryItem struct {
	Alias     string `json:"alias"`
	Format    string `json:"format"`
	Size      int64  `json:"size"`
	Extension string `json:"extension"`
	Path      string `json:"-"`
}

type Metadata struct {
	Width      int     `json:"width"`
	Height     int     `json:"height"`
	MinLayer   int     `json:"minLayer"`
	MaxLayer   int     `json:"maxLayer"`
	LevelCount int     `json:"levelCount"`
	TileWidth  int     `json:"tileWidth"`
	TileHeight int     `json:"tileHeight"`
	MPP        float32 `json:"mpp,omitempty"`
	ScanScale  float32 `json:"magnification,omitempty"`
	Levels     []Level `json:"levels"`
}

type Level struct {
	Level      int `json:"level"`
	Width      int `json:"width"`
	Height     int `json:"height"`
	TileWidth  int `json:"tileWidth"`
	TileHeight int `json:"tileHeight"`
	Cols       int `json:"cols"`
	Rows       int `json:"rows"`
}

type AssetResult struct {
	Status      string   `json:"status"`
	Bytes       int      `json:"bytes,omitempty"`
	Width       int      `json:"width,omitempty"`
	Height      int      `json:"height,omitempty"`
	ContentType string   `json:"contentType,omitempty"`
	Warnings    []string `json:"warnings,omitempty"`
	Error       string   `json:"error,omitempty"`
}

type TileCase struct {
	Kind     string   `json:"kind"`
	Level    int      `json:"level"`
	X        int      `json:"x"`
	Y        int      `json:"y"`
	Status   string   `json:"status"`
	Duration float64  `json:"durationMs"`
	Width    int      `json:"width,omitempty"`
	Height   int      `json:"height,omitempty"`
	Warnings []string `json:"warnings,omitempty"`
	Error    string   `json:"error,omitempty"`
}

type TileSummary struct {
	Total   int        `json:"total"`
	Success int        `json:"success"`
	Failed  int        `json:"failed"`
	Empty   int        `json:"empty"`
	Cases   []TileCase `json:"cases,omitempty"`
}

type TimingResult struct {
	Samples int     `json:"samples"`
	MinMs   float64 `json:"minMs,omitempty"`
	AvgMs   float64 `json:"avgMs,omitempty"`
	P95Ms   float64 `json:"p95Ms,omitempty"`
	MaxMs   float64 `json:"maxMs,omitempty"`
	Failed  int     `json:"failed"`
}

type ConcurrencyResult struct {
	Workers   int     `json:"workers"`
	Success   int     `json:"success"`
	Failed    int     `json:"failed"`
	ElapsedMs float64 `json:"elapsedMs"`
}

type RuntimeSnapshot struct {
	HeapAlloc uint64 `json:"heapAlloc"`
	RSS       int64  `json:"rss,omitempty"`
	Goroutine int    `json:"goroutine"`
	FD        int    `json:"fd,omitempty"`
}

type StabilityResult struct {
	Total   int             `json:"total"`
	Success int             `json:"success"`
	Failed  int             `json:"failed"`
	Before  RuntimeSnapshot `json:"before"`
	After   RuntimeSnapshot `json:"after"`
	Errors  []string        `json:"errors,omitempty"`
}

type Result struct {
	Alias             string              `json:"alias"`
	Format            string              `json:"format"`
	Size              int64               `json:"size"`
	Extension         string              `json:"extension"`
	DetectedFormat    string              `json:"detectedFormat,omitempty"`
	DetectionEvidence string              `json:"detectionEvidence,omitempty"`
	Engine            string              `json:"engine,omitempty"`
	SourceStatus      string              `json:"sourceStatus,omitempty"`
	Metadata          *Metadata           `json:"metadata,omitempty"`
	Thumbnail         AssetResult         `json:"thumbnail"`
	Label             AssetResult         `json:"label"`
	Macro             AssetResult         `json:"macro"`
	Tiles             TileSummary         `json:"tiles"`
	RandomTiles       TileSummary         `json:"randomTiles"`
	Performance       TimingResult        `json:"performance"`
	Concurrency       []ConcurrencyResult `json:"concurrency,omitempty"`
	Stability         StabilityResult     `json:"stability"`
	Status            string              `json:"status"`
	FailureStage      string              `json:"failureStage,omitempty"`
	Error             string              `json:"error,omitempty"`
}

type Report struct {
	GeneratedAt string   `json:"generatedAt"`
	Options     Options  `json:"options"`
	Samples     []Result `json:"samples"`
}

func Inventory(root string) ([]InventoryItem, error) {
	root, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	var items []InventoryItem
	err = filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !entry.Type().IsRegular() {
			return nil
		}
		extension := strings.ToLower(filepath.Ext(path))
		format, ok := targetFormats[extension]
		if !ok {
			return nil
		}
		info, statErr := entry.Info()
		if statErr != nil || info.Size() <= 0 {
			return statErr
		}
		items = append(items, InventoryItem{Format: format, Size: info.Size(), Extension: extension, Path: path})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].Format != items[j].Format {
			return items[i].Format < items[j].Format
		}
		if items[i].Size != items[j].Size {
			return items[i].Size < items[j].Size
		}
		return items[i].Path < items[j].Path
	})
	counts := map[string]int{}
	for index := range items {
		counts[items[index].Format]++
		items[index].Alias = fmt.Sprintf("%s_SAMPLE_%02d", items[index].Format, counts[items[index].Format])
	}
	return items, nil
}

func Verify(items []InventoryItem, options Options) Report {
	report := Report{GeneratedAt: time.Now().UTC().Format(time.RFC3339), Options: options}
	for _, item := range items {
		report.Samples = append(report.Samples, verifyOne(item, options))
	}
	return report
}

func verifyOne(item InventoryItem, options Options) Result {
	result := Result{Alias: item.Alias, Format: item.Format, Size: item.Size, Extension: item.Extension,
		Thumbnail: AssetResult{Status: "NOT_RUN"}, Label: AssetResult{Status: "NOT_RUN"}, Macro: AssetResult{Status: "NOT_RUN"}, Status: "FAILED"}
	if item.Format == "SVS" {
		result.Status = "DEPENDENCY_REQUIRED"
		result.FailureStage = "parser_route"
		result.Error = "SVS is intentionally verified through slide-worker/OpenSlide"
		return result
	}
	parserRegistry := registry.New()
	parser, capability, err := parserRegistry.Open(item.Path)
	result.Engine = capability.Engine
	result.SourceStatus = string(capability.Status)
	if err != nil {
		result.Status = classifyOpenFailure(capability, err)
		result.FailureStage = "detect"
		result.Error = sanitize(err.Error(), item)
		return result
	}
	result.DetectedFormat = capability.Format
	result.DetectionEvidence = "extension route plus parser signature/structure checks and validated metadata"
	if item.Format != "" && capability.Format != item.Format {
		result.FailureStage = "detect"
		result.Error = fmt.Sprintf("detected %s, expected %s", capability.Format, item.Format)
		return result
	}
	header, err := parser.GetHeaderInfoFunc()
	if err != nil {
		result.FailureStage = "metadata"
		result.Error = sanitize(err.Error(), item)
		return result
	}
	metadata := metadataFromHeader(header)
	result.Metadata = &metadata
	result.Thumbnail = verifyAsset(item, parser.GetThumbnailImagePathFunc)
	result.Label = verifyAsset(item, parser.GetLabelInfoPathFunc)
	result.Macro = verifyAsset(item, parser.GetMacrograph)

	basicCoordinates := plannedCoordinates(metadata)
	result.Tiles = runTiles(parser, header, basicCoordinates, item, true)
	randomCoordinates := randomCoordinates(metadata, options.RandomTiles, options.Seed+int64(item.Size))
	result.RandomTiles = runTiles(parser, header, randomCoordinates, item, false)
	result.Performance = runPerformance(parser, header, metadata, item, options.Performance, options.Seed+7)
	for _, workers := range options.Concurrency {
		if workers > 0 {
			result.Concurrency = append(result.Concurrency, runConcurrency(item, metadata, workers, options.Seed+int64(workers)))
		}
	}
	result.Stability = runStability(parser, header, metadata, item, options.Stability, options.Seed+99)

	if result.Tiles.Success == 0 || result.Tiles.Failed > 0 {
		result.Status, result.FailureStage = "PARTIAL", "tiles"
		result.Error = "one or more required tile checks failed"
		return result
	}
	if result.RandomTiles.Failed > 0 || result.RandomTiles.Success == 0 {
		result.Status, result.FailureStage = "PARTIAL", "random_tiles"
		result.Error = "random tile validation failed"
		return result
	}
	if result.Thumbnail.Status != "PASS" {
		result.Status, result.FailureStage = "PARTIAL", "thumbnail"
		result.Error = "thumbnail validation failed"
		return result
	}
	for _, concurrency := range result.Concurrency {
		if concurrency.Failed > 0 {
			result.Status, result.FailureStage = "FAILED", "concurrency"
			result.Error = "concurrent parser validation failed"
			return result
		}
	}
	if result.Stability.Failed > 0 {
		result.Status, result.FailureStage = "FAILED", "stability"
		result.Error = "continuous tile validation failed"
		return result
	}
	result.Status = "REAL_SAMPLE_PASS"
	return result
}

func metadataFromHeader(header types.HeaderInfo) Metadata {
	levelCount := header.MaxLayer - header.MinLayer + 1
	metadata := Metadata{Width: header.Width, Height: header.Height, MinLayer: header.MinLayer, MaxLayer: header.MaxLayer,
		LevelCount: levelCount, TileWidth: header.BlockSize, TileHeight: header.BlockSize, MPP: header.Mpp, ScanScale: header.KhiScanScale}
	for browserLevel := 0; browserLevel < levelCount; browserLevel++ {
		downsample := math.Pow(2, float64(levelCount-1-browserLevel))
		width := max(1, int(math.Ceil(float64(header.Width)/downsample)))
		height := max(1, int(math.Ceil(float64(header.Height)/downsample)))
		metadata.Levels = append(metadata.Levels, Level{Level: browserLevel, Width: width, Height: height,
			TileWidth: header.BlockSize, TileHeight: header.BlockSize, Cols: ceilDiv(width, header.BlockSize), Rows: ceilDiv(height, header.BlockSize)})
	}
	return metadata
}

func verifyAsset(item InventoryItem, write func(io.Writer) error) AssetResult {
	buffer := &bytes.Buffer{}
	if err := write(buffer); err != nil {
		message := sanitize(err.Error(), item)
		status := "FAILED"
		if isNotAvailable(message) {
			status = "NOT_AVAILABLE"
		}
		return AssetResult{Status: status, Error: message}
	}
	return inspectImage(buffer.Bytes())
}

func inspectImage(data []byte) AssetResult {
	if len(data) == 0 {
		return AssetResult{Status: "FAILED", Error: "empty image"}
	}
	decoded, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return AssetResult{Status: "FAILED", Bytes: len(data), ContentType: http.DetectContentType(data), Error: "undecodable image: " + err.Error()}
	}
	bounds := decoded.Bounds()
	if bounds.Dx() <= 0 || bounds.Dy() <= 0 {
		return AssetResult{Status: "FAILED", Bytes: len(data), Error: "invalid image dimensions"}
	}
	warnings := imageWarnings(decoded)
	return AssetResult{Status: "PASS", Bytes: len(data), Width: bounds.Dx(), Height: bounds.Dy(), ContentType: http.DetectContentType(data), Warnings: warnings}
}

func imageWarnings(decoded image.Image) []string {
	bounds := decoded.Bounds()
	stepX, stepY := max(1, bounds.Dx()/32), max(1, bounds.Dy()/32)
	allBlack, allWhite, allTransparent := true, true, true
	for y := bounds.Min.Y; y < bounds.Max.Y; y += stepY {
		for x := bounds.Min.X; x < bounds.Max.X; x += stepX {
			r, g, b, a := decoded.At(x, y).RGBA()
			if r > 512 || g > 512 || b > 512 {
				allBlack = false
			}
			if r < 65023 || g < 65023 || b < 65023 {
				allWhite = false
			}
			if a > 512 {
				allTransparent = false
			}
		}
	}
	var warnings []string
	if allBlack {
		warnings = append(warnings, "ALL_BLACK")
	}
	if allWhite {
		warnings = append(warnings, "ALL_WHITE")
	}
	if allTransparent {
		warnings = append(warnings, "ALL_TRANSPARENT")
	}
	return warnings
}

func plannedCoordinates(metadata Metadata) []TileCase {
	last := metadata.LevelCount - 1
	middle := last / 2
	var planned []TileCase
	add := func(kind string, level, x, y int) {
		candidate := TileCase{Kind: kind, Level: level, X: x, Y: y}
		for _, existing := range planned {
			if existing.Level == level && existing.X == x && existing.Y == y {
				return
			}
		}
		planned = append(planned, candidate)
	}
	for _, level := range []int{0} {
		info := metadata.Levels[level]
		add("low_top_left", level, 0, 0)
		add("low_center", level, (info.Cols-1)/2, (info.Rows-1)/2)
		add("low_bottom_right", level, info.Cols-1, info.Rows-1)
	}
	info := metadata.Levels[middle]
	add("middle_top_left", middle, 0, 0)
	add("middle_center", middle, (info.Cols-1)/2, (info.Rows-1)/2)
	info = metadata.Levels[last]
	add("high_top_left", last, 0, 0)
	add("high_center", last, (info.Cols-1)/2, (info.Rows-1)/2)
	add("high_bottom_right", last, info.Cols-1, info.Rows-1)
	add("edge", last, info.Cols-1, (info.Rows-1)/2)
	return planned
}

func randomCoordinates(metadata Metadata, count int, seed int64) []TileCase {
	if count <= 0 {
		return nil
	}
	random := rand.New(rand.NewSource(seed))
	coordinates := make([]TileCase, 0, count)
	for index := 0; index < count; index++ {
		level := random.Intn(metadata.LevelCount)
		info := metadata.Levels[level]
		coordinates = append(coordinates, TileCase{Kind: "random", Level: level, X: random.Intn(max(1, info.Cols)), Y: random.Intn(max(1, info.Rows))})
	}
	return coordinates
}

func runTiles(parser types.ImageParser, header types.HeaderInfo, coordinates []TileCase, item InventoryItem, keepCases bool) TileSummary {
	summary := TileSummary{Total: len(coordinates)}
	for _, coordinate := range coordinates {
		result := executeTile(parser, header, coordinate, item)
		switch result.Status {
		case "PASS":
			summary.Success++
		case "EMPTY_TILE":
			summary.Empty++
		default:
			summary.Failed++
		}
		if keepCases || result.Status != "PASS" {
			summary.Cases = append(summary.Cases, result)
		}
	}
	return summary
}

func executeTile(parser types.ImageParser, header types.HeaderInfo, coordinate TileCase, item InventoryItem) TileCase {
	started := time.Now()
	buffer := &bytes.Buffer{}
	err := parser.GetImage(coordinate.Level+header.MinLayer, coordinate.X, coordinate.Y, buffer)
	coordinate.Duration = milliseconds(time.Since(started))
	if err != nil {
		message := sanitize(err.Error(), item)
		coordinate.Error = message
		if isEmptyTileError(message) {
			coordinate.Status = "EMPTY_TILE"
		} else {
			coordinate.Status = "FAILED"
		}
		return coordinate
	}
	imageResult := inspectImage(buffer.Bytes())
	coordinate.Status, coordinate.Width, coordinate.Height = imageResult.Status, imageResult.Width, imageResult.Height
	coordinate.Warnings, coordinate.Error = imageResult.Warnings, imageResult.Error
	return coordinate
}

func runPerformance(parser types.ImageParser, header types.HeaderInfo, metadata Metadata, item InventoryItem, count int, seed int64) TimingResult {
	result := TimingResult{Samples: count}
	var durations []float64
	for _, coordinate := range randomCoordinates(metadata, count, seed) {
		tile := executeTile(parser, header, coordinate, item)
		if tile.Status != "PASS" {
			result.Failed++
			continue
		}
		durations = append(durations, tile.Duration)
	}
	if len(durations) == 0 {
		return result
	}
	sort.Float64s(durations)
	var total float64
	for _, duration := range durations {
		total += duration
	}
	result.MinMs, result.MaxMs = durations[0], durations[len(durations)-1]
	result.AvgMs = total / float64(len(durations))
	p95Index := max(0, int(math.Ceil(float64(len(durations))*0.95))-1)
	result.P95Ms = durations[p95Index]
	return result
}

func runConcurrency(item InventoryItem, metadata Metadata, workers int, seed int64) ConcurrencyResult {
	result := ConcurrencyResult{Workers: workers}
	coordinates := randomCoordinates(metadata, workers, seed)
	started := time.Now()
	var wait sync.WaitGroup
	var mu sync.Mutex
	for _, coordinate := range coordinates {
		coordinate := coordinate
		wait.Add(1)
		go func() {
			defer wait.Done()
			parser, _, err := registry.New().Open(item.Path)
			if err == nil {
				header, headerErr := parser.GetHeaderInfoFunc()
				if headerErr != nil {
					err = headerErr
				} else if tile := executeTile(parser, header, coordinate, item); tile.Status != "PASS" && tile.Status != "EMPTY_TILE" {
					err = errors.New(tile.Error)
				}
			}
			mu.Lock()
			if err != nil {
				result.Failed++
			} else {
				result.Success++
			}
			mu.Unlock()
		}()
	}
	wait.Wait()
	result.ElapsedMs = milliseconds(time.Since(started))
	return result
}

func runStability(parser types.ImageParser, header types.HeaderInfo, metadata Metadata, item InventoryItem, count int, seed int64) StabilityResult {
	result := StabilityResult{Total: count, Before: runtimeSnapshot()}
	for _, coordinate := range randomCoordinates(metadata, count, seed) {
		tile := executeTile(parser, header, coordinate, item)
		if tile.Status == "PASS" || tile.Status == "EMPTY_TILE" {
			result.Success++
			continue
		}
		result.Failed++
		if len(result.Errors) < 10 {
			result.Errors = append(result.Errors, fmt.Sprintf("level=%d x=%d y=%d: %s", tile.Level, tile.X, tile.Y, tile.Error))
		}
	}
	runtime.GC()
	result.After = runtimeSnapshot()
	return result
}

func runtimeSnapshot() RuntimeSnapshot {
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	snapshot := RuntimeSnapshot{HeapAlloc: memory.HeapAlloc, Goroutine: runtime.NumGoroutine()}
	if entries, err := os.ReadDir("/proc/self/fd"); err == nil {
		snapshot.FD = len(entries)
	}
	if data, err := os.ReadFile("/proc/self/statm"); err == nil {
		fields := strings.Fields(string(data))
		if len(fields) > 1 {
			if pages, parseErr := strconv.ParseInt(fields[1], 10, 64); parseErr == nil {
				snapshot.RSS = pages * int64(os.Getpagesize())
			}
		}
	}
	return snapshot
}

func classifyOpenFailure(capability registry.Capability, err error) string {
	message := err.Error()
	if capability.Format == "CSP" || strings.Contains(message, "SDK_BUNDLED") {
		return "LICENSE_REQUIRED"
	}
	if capability.Format == "HWP" || capability.Format == "TRON" || strings.Contains(message, "SDK_REQUIRED") {
		return "DEPENDENCY_REQUIRED"
	}
	if strings.Contains(message, "DECODER_REQUIRED") {
		return "DEPENDENCY_REQUIRED"
	}
	return "FAILED"
}

func sanitize(message string, item InventoryItem) string {
	if item.Path != "" {
		message = strings.ReplaceAll(message, item.Path, item.Alias)
		message = strings.ReplaceAll(message, filepath.Base(item.Path), item.Alias)
	}
	return message
}

func isNotAvailable(message string) bool {
	upper := strings.ToUpper(message)
	return strings.Contains(upper, "NOT_AVAILABLE") || strings.Contains(upper, "NOT EXIST") || strings.Contains(upper, "NO LABEL") || strings.Contains(upper, "NO MACRO")
}

func isEmptyTileError(message string) bool {
	upper := strings.ToUpper(message)
	return strings.Contains(upper, "EMPTY_TILE") || strings.Contains(upper, "NOT_STORED") || strings.Contains(upper, "TILE NOT EXIST")
}

func ceilDiv(value, divisor int) int {
	if divisor <= 0 {
		return 0
	}
	return max(1, (value+divisor-1)/divisor)
}

func milliseconds(duration time.Duration) float64 {
	return float64(duration.Microseconds()) / 1000
}

func Encode(report Report, writer io.Writer) error {
	encoder := json.NewEncoder(writer)
	encoder.SetIndent("", "  ")
	return encoder.Encode(report)
}
