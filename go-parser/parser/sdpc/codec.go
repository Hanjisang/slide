package sdpc

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"os/exec"
	"time"
)

const (
	maxHEVCPayload = 64 << 20
	decoderTimeout = 10 * time.Second
)

type Decoder interface {
	Decode(payload []byte, width, height int) (image.Image, error)
}

type FFmpegDecoder struct {
	Binary  string
	Timeout time.Duration
}

func NewFFmpegDecoder() *FFmpegDecoder { return &FFmpegDecoder{Binary: "ffmpeg", Timeout: decoderTimeout} }

func (d *FFmpegDecoder) Decode(payload []byte, width, height int) (image.Image, error) {
	if len(payload) == 0 || len(payload) > maxHEVCPayload {
		return nil, errors.New("HEVC_PAYLOAD_OUT_OF_RANGE")
	}
	if width <= 0 || height <= 0 || width > 4096 || height > 4096 {
		return nil, errors.New("HEVC_DIMENSIONS_OUT_OF_RANGE")
	}
	if d == nil || d.Binary == "" {
		return nil, ErrDecoderRequired
	}
	timeout := d.Timeout
	if timeout <= 0 {
		timeout = decoderTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, d.Binary, "-hide_banner", "-loglevel", "error", "-f", "hevc", "-i", "pipe:0", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1")
	cmd.Stdin = bytes.NewReader(payload)
	var output, stderr bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if errors.Is(err, exec.ErrNotFound) {
			return nil, ErrDecoderRequired
		}
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return nil, errors.New("HEVC_DECODER_TIMEOUT")
		}
		if stderr.Len() > 512 {
			return nil, fmt.Errorf("HEVC_DECODER_FAILED: %s", stderr.String()[:512])
		}
		return nil, fmt.Errorf("HEVC_DECODER_FAILED: %w: %s", err, stderr.String())
	}
	expected := width * height * 4
	if expected <= 0 || output.Len() != expected {
		return nil, fmt.Errorf("HEVC_DECODER_INVALID_OUTPUT: got %d bytes, expected %d", output.Len(), expected)
	}
	pixels := append([]byte(nil), output.Bytes()...)
	return &image.RGBA{Pix: pixels, Stride: width * 4, Rect: image.Rect(0, 0, width, height)}, nil
}
