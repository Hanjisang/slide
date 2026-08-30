package registry

import (
	"encoding/binary"
	"errors"
	"fmt"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strings"

	"imageparser/parser/csp"
	"imageparser/parser/dmetrix"
	"imageparser/parser/fenlan"
	"imageparser/parser/hwp"
	"imageparser/parser/kfb"
	"imageparser/parser/mdsx"
	"imageparser/parser/sdpc"
	"imageparser/parser/tmap"
	"imageparser/parser/tron"
	"imageparser/parser/zyp"
	"imageparser/types"
	"imageparser/utils/streamer"
)

type Status string

const (
	StatusAvailable             Status = "AVAILABLE"
	StatusTestDataRequired      Status = "TEST_DATA_REQUIRED"
	StatusLicenseRequired       Status = "LICENSE_REQUIRED"
	StatusCompatibilityRequired Status = "COMPATIBILITY_REQUIRED"
)

type Capability struct {
	Format         string   `json:"format"`
	Engine         string   `json:"engine"`
	Status         Status   `json:"status"`
	Extensions     []string `json:"extensions"`
	Build          bool     `json:"build"`
	Tested         bool     `json:"tested"`
	Missing        string   `json:"missingDependency,omitempty"`
	SourceIncluded bool     `json:"sourceIncluded"`
}

type constructor func(streamer.Streamer) (types.ImageParser, error)

type entry struct {
	Capability
	newParser constructor
}

type Registry struct {
	byExtension map[string]entry
	formats     []Capability
}

func New() *Registry {
	entries := []entry{
		{Capability: availableNative("KFB", ".kfb"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return kfb.New(s) }},
		{Capability: availableNative("TMAP", ".tmap"), newParser: tmap.New},
		{Capability: availableNative("MDSX", ".mdsx"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return mdsx.New(s) }},
		{Capability: availableNative("DMETRIX", ".dmetrix"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return dmetrix.New(s) }},
		{Capability: availableNative("FENLAN", ".fenlan"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return fenlan.New(s) }},
		{Capability: availableNative("ZYP", ".zyp"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return zyp.New(s) }},
		{Capability: Capability{Format: "SDPC", Engine: "GO_NATIVE", Status: StatusAvailable, Extensions: []string{".sdpc"}, Build: true, Tested: true, SourceIncluded: true}, newParser: func(s streamer.Streamer) (types.ImageParser, error) { return sdpc.New(s) }},
		{Capability: testDataRequired("CSP", "GO_NATIVE", ".csp"), newParser: csp.New},
		{Capability: Capability{Format: "HWP", Engine: "VENDOR_SDK_HELPER", Status: StatusAvailable, Extensions: []string{".hwp"}, Build: true, Tested: true, SourceIncluded: true}, newParser: hwp.New},
		{Capability: testDataRequired("TRON", "VENDOR_SDK_RUNTIME", ".tron"), newParser: tron.New},
	}
	r := &Registry{byExtension: make(map[string]entry, len(entries))}
	for _, item := range entries {
		r.formats = append(r.formats, item.Capability)
		for _, extension := range item.Extensions {
			r.byExtension[strings.ToLower(extension)] = item
		}
	}
	sort.Slice(r.formats, func(i, j int) bool { return r.formats[i].Format < r.formats[j].Format })
	return r
}

func availableNative(format, extension string) Capability {
	return Capability{Format: format, Engine: "GO_NATIVE", Status: StatusAvailable, Extensions: []string{extension}, Build: true, Tested: true, SourceIncluded: true}
}

func testDataRequired(format, engine, extension string) Capability {
	return Capability{Format: format, Engine: engine, Status: StatusTestDataRequired, Extensions: []string{extension}, Build: true, Tested: false, Missing: "manual real-slide validation", SourceIncluded: true}
}

func (r *Registry) Formats() []Capability {
	result := make([]Capability, len(r.formats))
	copy(result, r.formats)
	return result
}

func (r *Registry) Open(path string) (parser types.ImageParser, capability Capability, err error) {
	item, ok := r.byExtension[strings.ToLower(filepath.Ext(path))]
	if !ok {
		return nil, Capability{}, errors.New("UNSUPPORTED_FORMAT")
	}
	if item.newParser == nil {
		return nil, item.Capability, fmt.Errorf("%s: %s", item.Status, item.Missing)
	}
	defer func() {
		if recovered := recover(); recovered != nil {
			parser = nil
			err = fmt.Errorf("%s parser panic: %v", item.Format, recovered)
			_ = debug.Stack()
		}
	}()
	parser, err = item.newParser(streamer.NewFile(path, binary.LittleEndian))
	if err != nil {
		return nil, item.Capability, fmt.Errorf("%s parser initialization failed: %w", item.Format, err)
	}
	header, err := parser.GetHeaderInfoFunc()
	if err != nil {
		closeParser(parser)
		return nil, item.Capability, fmt.Errorf("%s metadata failed: %w", item.Format, err)
	}
	if err := validateHeader(header); err != nil {
		closeParser(parser)
		return nil, item.Capability, fmt.Errorf("%s invalid metadata: %w", item.Format, err)
	}
	return parser, item.Capability, nil
}

func closeParser(parser types.ImageParser) {
	if closeable, ok := parser.(types.CloseableParser); ok {
		_ = closeable.Close()
	}
}

func validateHeader(header types.HeaderInfo) error {
	if header.Width <= 0 || header.Height <= 0 || header.Width > 2_000_000 || header.Height > 2_000_000 {
		return fmt.Errorf("invalid dimensions %dx%d", header.Width, header.Height)
	}
	if header.MinLayer < 0 || header.MaxLayer < header.MinLayer || header.MaxLayer-header.MinLayer+1 > 32 {
		return fmt.Errorf("invalid layer range %d..%d", header.MinLayer, header.MaxLayer)
	}
	if header.BlockSize <= 0 || header.BlockSize > 4096 {
		return fmt.Errorf("invalid block size %d", header.BlockSize)
	}
	return nil
}
