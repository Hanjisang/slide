package registry

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCapabilitiesReflectRealSlideAcceptance(t *testing.T) {
	formats := New().Formats()
	if len(formats) != 10 {
		t.Fatalf("expected 10 Go/vendor formats, got %d", len(formats))
	}
	for _, format := range formats {
		completedNative := format.Engine == "GO_NATIVE" && format.Format != "CSP"
		if completedNative && (format.Status != StatusAvailable || !format.Tested || !format.Build) {
			t.Fatalf("%s must expose completed real-slide evidence", format.Format)
		}
		if format.Format == "CSP" || format.Format == "HWP" || format.Format == "TRON" {
			if format.Status != StatusTestDataRequired || !format.Build || format.Tested {
				t.Fatalf("%s must remain built but TEST_DATA_REQUIRED before manual validation: %+v", format.Format, format)
			}
		}
	}
}

func TestNativeParsersFailSafelyOnTruncatedData(t *testing.T) {
	registry := New()
	for _, extension := range []string{".kfb", ".tmap", ".mdsx", ".dmetrix", ".fenlan", ".zyp"} {
		t.Run(extension, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "broken"+extension)
			if err := os.WriteFile(path, make([]byte, 1<<20), 0o600); err != nil {
				t.Fatal(err)
			}
			if parser, _, err := registry.Open(path); err == nil || parser != nil {
				t.Fatalf("corrupt %s input was accepted", extension)
			}
		})
	}
}

func TestOptionalSDKFormatsRemainIsolated(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.hwp")
	if err := os.WriteFile(path, []byte("not an hwp"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, capability, err := New().Open(path)
	if err == nil || capability.Status != StatusTestDataRequired {
		t.Fatalf("expected isolated TEST_DATA_REQUIRED capability, got %q / %v", capability.Status, err)
	}
}
