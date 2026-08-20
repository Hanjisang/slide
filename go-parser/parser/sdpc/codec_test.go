package sdpc

import (
	"errors"
	"testing"
)

func TestFFmpegDecoderRejectsOversizedPayload(t *testing.T) {
	decoder := &FFmpegDecoder{Binary: "missing-ffmpeg"}
	if _, err := decoder.Decode(make([]byte, maxHEVCPayload+1), 672, 672); err == nil || err.Error() != "HEVC_PAYLOAD_OUT_OF_RANGE" {
		t.Fatalf("unexpected oversized payload error: %v", err)
	}
}

func TestFFmpegDecoderRejectsInvalidDimensionsBeforeProcess(t *testing.T) {
	decoder := &FFmpegDecoder{Binary: "missing-ffmpeg"}
	if _, err := decoder.Decode([]byte{0, 0, 1, 1}, 0, 672); err == nil || err.Error() != "HEVC_DIMENSIONS_OUT_OF_RANGE" {
		t.Fatalf("unexpected dimensions error: %v", err)
	}
}

func TestFFmpegDecoderReportsMissingBinary(t *testing.T) {
	decoder := &FFmpegDecoder{Binary: "missing-ffmpeg"}
	if _, err := decoder.Decode([]byte{0, 0, 1, 1}, 672, 672); !errors.Is(err, ErrDecoderRequired) {
		t.Fatalf("expected decoder-required error, got %v", err)
	}
}
