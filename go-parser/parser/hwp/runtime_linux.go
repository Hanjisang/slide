//go:build linux

package hwp

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"math"
	"os"
	"os/exec"
	"sync"
	"time"
)

const (
	maxSDKImageBytes = 64 << 20
	configHeaderSize = 44
	frameRecordSize  = 28
	imageHeaderSize  = 28
)

type helperProcess struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout io.ReadCloser
	config config
	mu     sync.Mutex
}

var helperRegistry = struct {
	sync.Mutex
	next    uintptr
	readers map[uintptr]*helperProcess
}{next: 1, readers: make(map[uintptr]*helperProcess)}

type platformRuntime struct{}

func hwpSDKPath() string {
	if path := os.Getenv("HWP_SDK_PATH"); path != "" {
		return path
	}
	for _, path := range []string{"/opt/vendor-libs/hwp/libhwp_sdk.so", "vendor-libs-local/hwp/libhwp_sdk.so", "../vendor-libs-local/hwp/libhwp_sdk.so"} {
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}
	return "/opt/vendor-libs/hwp/libhwp_sdk.so"
}

func hwpHelperPath() string {
	if path := os.Getenv("HWP_HELPER_PATH"); path != "" {
		return path
	}
	return "/usr/local/bin/hwp-helper"
}

func readProtocolError(reader io.Reader, length uint32) error {
	if length == 0 {
		return nil
	}
	if length > 4096 {
		return fmt.Errorf("HWP helper returned an oversized error message (%d bytes)", length)
	}
	message := make([]byte, length)
	if _, err := io.ReadFull(reader, message); err != nil {
		return fmt.Errorf("read HWP helper error: %w", err)
	}
	return errors.New(string(message))
}

func stopHelper(process *helperProcess) {
	if process == nil || process.cmd == nil || process.cmd.Process == nil {
		return
	}
	_ = process.stdin.Close()
	done := make(chan error, 1)
	go func() { done <- process.cmd.Wait() }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		_ = process.cmd.Process.Kill()
		<-done
	}
}

func (platformRuntime) open(file string) (uintptr, error) {
	cmd := exec.Command(hwpHelperPath(), hwpSDKPath(), file)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return 0, fmt.Errorf("HWP helper stdin: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return 0, fmt.Errorf("HWP helper stdout: %w", err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		return 0, fmt.Errorf("start %s: %w", hwpHelperPath(), err)
	}
	process := &helperProcess{cmd: cmd, stdin: stdin, stdout: stdout}
	header := make([]byte, configHeaderSize)
	if _, err := io.ReadFull(stdout, header); err != nil {
		stopHelper(process)
		return 0, fmt.Errorf("HWP helper exited during open: %w", err)
	}
	if string(header[:4]) != "HWPC" {
		stopHelper(process)
		return 0, fmt.Errorf("invalid HWP helper config response %q", header[:4])
	}
	status := int32(binary.LittleEndian.Uint32(header[4:8]))
	frameCount := binary.LittleEndian.Uint32(header[36:40])
	protocolErr := readProtocolError(stdout, binary.LittleEndian.Uint32(header[40:44]))
	if status != 0 || protocolErr != nil {
		stopHelper(process)
		if protocolErr != nil {
			return 0, protocolErr
		}
		return 0, fmt.Errorf("HWP helper open returned %d", status)
	}
	process.config = config{
		tileWidth:   binary.LittleEndian.Uint32(header[8:12]),
		tileHeight:  binary.LittleEndian.Uint32(header[12:16]),
		imageWidth:  binary.LittleEndian.Uint32(header[16:20]),
		imageHeight: binary.LittleEndian.Uint32(header[20:24]),
		scanRatio:   math.Float32frombits(binary.LittleEndian.Uint32(header[24:28])),
		downsample:  math.Float32frombits(binary.LittleEndian.Uint32(header[28:32])),
		mpp:         math.Float32frombits(binary.LittleEndian.Uint32(header[32:36])),
	}
	if frameCount == 0 || frameCount > 32 {
		stopHelper(process)
		return 0, fmt.Errorf("invalid HWP helper frame count: %d", frameCount)
	}
	for index := uint32(0); index < frameCount; index++ {
		record := make([]byte, frameRecordSize)
		if _, err := io.ReadFull(stdout, record); err != nil {
			stopHelper(process)
			return 0, fmt.Errorf("read HWP helper frame %d: %w", index, err)
		}
		level := hwpLevel{
			ratio:   math.Float32frombits(binary.LittleEndian.Uint32(record[0:4])),
			width:   binary.LittleEndian.Uint32(record[4:8]),
			height:  binary.LittleEndian.Uint32(record[8:12]),
			originX: binary.LittleEndian.Uint32(record[12:16]),
			originY: binary.LittleEndian.Uint32(record[16:20]),
		}
		if level.width == 0 || level.height == 0 || level.ratio <= 0 || math.IsNaN(float64(level.ratio)) || math.IsInf(float64(level.ratio), 0) {
			stopHelper(process)
			return 0, fmt.Errorf("invalid HWP helper frame %d", index)
		}
		process.config.levels = append(process.config.levels, level)
	}

	helperRegistry.Lock()
	handle := helperRegistry.next
	helperRegistry.next++
	helperRegistry.readers[handle] = process
	helperRegistry.Unlock()
	return handle, nil
}

func takeHelper(reader uintptr) *helperProcess {
	helperRegistry.Lock()
	defer helperRegistry.Unlock()
	process := helperRegistry.readers[reader]
	delete(helperRegistry.readers, reader)
	return process
}

func findHelper(reader uintptr) (*helperProcess, error) {
	helperRegistry.Lock()
	defer helperRegistry.Unlock()
	process := helperRegistry.readers[reader]
	if process == nil {
		return nil, errors.New("HWP helper reader is closed")
	}
	return process, nil
}

func (platformRuntime) close(reader uintptr) {
	process := takeHelper(reader)
	if process == nil {
		return
	}
	process.mu.Lock()
	request := make([]byte, 16)
	binary.LittleEndian.PutUint32(request[:4], 5)
	_, _ = process.stdin.Write(request)
	process.mu.Unlock()
	stopHelper(process)
}

func (platformRuntime) config(reader uintptr) (config, error) {
	process, err := findHelper(reader)
	if err != nil {
		return config{}, err
	}
	return process.config, nil
}

func helperImage(reader uintptr, operation, x, y uint32, scale float32) ([]byte, uint32, uint32, error) {
	process, err := findHelper(reader)
	if err != nil {
		return nil, 0, 0, err
	}
	process.mu.Lock()
	defer process.mu.Unlock()
	request := make([]byte, 16)
	binary.LittleEndian.PutUint32(request[0:4], operation)
	binary.LittleEndian.PutUint32(request[4:8], x)
	binary.LittleEndian.PutUint32(request[8:12], y)
	binary.LittleEndian.PutUint32(request[12:16], math.Float32bits(scale))
	if _, err := process.stdin.Write(request); err != nil {
		return nil, 0, 0, fmt.Errorf("write HWP helper request: %w", err)
	}
	header := make([]byte, imageHeaderSize)
	if _, err := io.ReadFull(process.stdout, header); err != nil {
		return nil, 0, 0, fmt.Errorf("HWP helper exited during image read: %w", err)
	}
	if string(header[:4]) != "HWPI" {
		return nil, 0, 0, fmt.Errorf("invalid HWP helper image response %q", header[:4])
	}
	status := int32(binary.LittleEndian.Uint32(header[4:8]))
	width := binary.LittleEndian.Uint32(header[8:12])
	height := binary.LittleEndian.Uint32(header[12:16])
	length := binary.LittleEndian.Uint64(header[16:24])
	protocolErr := readProtocolError(process.stdout, binary.LittleEndian.Uint32(header[24:28]))
	if status != 0 || protocolErr != nil {
		if protocolErr != nil {
			return nil, 0, 0, protocolErr
		}
		return nil, 0, 0, fmt.Errorf("HWP helper image returned %d", status)
	}
	if length == 0 {
		return nil, 0, 0, errors.New("HWP SDK returned an empty image")
	}
	if length > maxSDKImageBytes {
		return nil, 0, 0, fmt.Errorf("HWP SDK image exceeds %d-byte safety limit", maxSDKImageBytes)
	}
	data := make([]byte, int(length))
	if _, err := io.ReadFull(process.stdout, data); err != nil {
		return nil, 0, 0, fmt.Errorf("read HWP helper image: %w", err)
	}
	data, err = normalizeHWPImage(data, width, height)
	if err != nil {
		return nil, 0, 0, err
	}
	return data, width, height, nil
}

func normalizeHWPImage(data []byte, width, height uint32) ([]byte, error) {
	pixels := uint64(width) * uint64(height)
	channels := uint64(0)
	if pixels > 0 && uint64(len(data)) == pixels*3 {
		channels = 3
	} else if pixels > 0 && uint64(len(data)) == pixels*4 {
		channels = 4
	} else {
		return data, nil
	}
	img := image.NewRGBA(image.Rect(0, 0, int(width), int(height)))
	for source, target := 0, 0; source < len(data); source, target = source+int(channels), target+4 {
		img.Pix[target] = data[source]
		img.Pix[target+1] = data[source+1]
		img.Pix[target+2] = data[source+2]
		img.Pix[target+3] = 0xff
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, img, &jpeg.Options{Quality: 92}); err != nil {
		return nil, fmt.Errorf("encode HWP SDK image: %w", err)
	}
	return output.Bytes(), nil
}

func (platformRuntime) readImage(reader uintptr, x, y uint32, scale float32) ([]byte, uint32, uint32, error) {
	return helperImage(reader, 1, x, y, scale)
}

func (platformRuntime) readNamed(reader uintptr, kind int) ([]byte, uint32, uint32, error) {
	if kind < imagePreview || kind > imageThumb {
		return nil, 0, 0, errors.New("invalid HWP named-image kind")
	}
	return helperImage(reader, uint32(kind+2), 0, 0, 0)
}
