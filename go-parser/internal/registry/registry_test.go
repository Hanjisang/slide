package registry

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCapabilitiesDoNotClaimAvailabilityWithoutRealSlides(t *testing.T) {
	formats := New().Formats()
	if len(formats) != 10 {
		t.Fatalf("expected 10 Go/vendor formats, got %d", len(formats))
	}
	for _, format := range formats {
		if format.Status == "AVAILABLE" && format.Format != "DMETRIX" && format.Format != "FENLAN" && format.Format != "SDPC" {
			t.Fatalf("%s must not be AVAILABLE without L5 evidence", format.Format)
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
	if err == nil || capability.Status != StatusSDKRequired {
		t.Fatalf("expected isolated SDK_REQUIRED capability, got %q / %v", capability.Status, err)
	}
}

func TestOptionalSDKFormatsRemainBuildableWithoutLibraries(t *testing.T) {
	for _, format := range New().Formats() {
		if (format.Format == "HWP" || format.Format == "TRON") && format.Status == StatusSDKPresent {
			t.Fatalf("%s unexpectedly claims SDK_PRESENT in a test environment without a checked-in SDK", format.Format)
		}
	}
}
