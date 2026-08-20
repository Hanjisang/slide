package registry

import (
	"encoding/binary"
	"errors"
	"fmt"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strings"

	"imageparser/parser/dmetrix"
	"imageparser/parser/fenlan"
	"imageparser/parser/kfb"
	"imageparser/parser/mdsx"
	"imageparser/parser/sdpc"
	"imageparser/parser/tmap"
	"imageparser/parser/zyp"
	"imageparser/types"
	"imageparser/utils/streamer"
)

type Status string

const (
	StatusTestDataRequired Status = "TEST_DATA_REQUIRED"
	StatusDecoderRequired  Status = "DECODER_REQUIRED"
	StatusSDKBundled       Status = "SDK_BUNDLED"
	StatusSDKRequired      Status = "SDK_REQUIRED"
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
		{Capability: native("KFB", ".kfb"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return kfb.New(s) }},
		{Capability: native("TMAP", ".tmap"), newParser: tmap.New},
		{Capability: native("MDSX", ".mdsx"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return mdsx.New(s) }},
		{Capability: availableNative("DMETRIX", ".dmetrix"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return dmetrix.New(s) }},
		{Capability: availableNative("FENLAN", ".fenlan"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return fenlan.New(s) }},
		{Capability: native("ZYP", ".zyp"), newParser: func(s streamer.Streamer) (types.ImageParser, error) { return zyp.New(s) }},
		{Capability: Capability{Format: "SDPC", Engine: "GO_NATIVE", Status: StatusDecoderRequired, Extensions: []string{".sdpc"}, Build: true, Missing: "libDecodeHevc.so for HEVC slides", SourceIncluded: true}, newParser: func(s streamer.Streamer) (types.ImageParser, error) { return sdpc.New(s) }},
		{Capability: Capability{Format: "CSP", Engine: "GO_CGO", Status: StatusSDKBundled, Extensions: []string{".csp"}, Build: false, Missing: "authorized libcsp_sdk.so in vendor-libs", SourceIncluded: false}},
		{Capability: Capability{Format: "HWP", Engine: "VENDOR_SDK", Status: StatusSDKRequired, Extensions: []string{".hwp"}, Build: false, Missing: "libhwp_sdk.so", SourceIncluded: false}},
		{Capability: Capability{Format: "TRON", Engine: "VENDOR_SDK", Status: StatusSDKRequired, Extensions: []string{".tron"}, Build: false, Missing: "libtronc.so", SourceIncluded: false}},
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

func native(format, extension string) Capability {
	return Capability{Format: format, Engine: "GO_NATIVE", Status: StatusTestDataRequired, Extensions: []string{extension}, Build: true, Tested: false, Missing: "real vendor slide for L3-L5 validation", SourceIncluded: true}
}

func availableNative(format, extension string) Capability {
	return Capability{Format: format, Engine: "GO_NATIVE", Status: "AVAILABLE", Extensions: []string{extension}, Build: true, Tested: true, SourceIncluded: true}
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
		return nil, item.Capability, fmt.Errorf("%s metadata failed: %w", item.Format, err)
	}
	if err := validateHeader(header); err != nil {
		return nil, item.Capability, fmt.Errorf("%s invalid metadata: %w", item.Format, err)
	}
	return parser, item.Capability, nil
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
