package streamer

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

func TestFileStreamerReadsBoundedRanges(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.bin")
	if err := os.WriteFile(path, []byte{1, 0, 2, 0, 3, 0}, 0o600); err != nil {
		t.Fatal(err)
	}
	file := NewFile(path, binary.LittleEndian)
	var values [2]uint16
	if err := file.Range2Type(0, 4, &values); err != nil {
		t.Fatal(err)
	}
	if values != [2]uint16{1, 2} {
		t.Fatalf("unexpected decoded values: %v", values)
	}
	var output bytes.Buffer
	if err := file.Range2Writer(2, 2, &output); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(output.Bytes(), []byte{2, 0}) {
		t.Fatalf("unexpected bytes: %v", output.Bytes())
	}
}

func TestFileStreamerRejectsUnsafeRanges(t *testing.T) {
	path := filepath.Join(t.TempDir(), "slide.bin")
	if err := os.WriteFile(path, make([]byte, 32), 0o600); err != nil {
		t.Fatal(err)
	}
	file := NewFile(path, binary.LittleEndian)
	tests := []struct{ offset, size int64 }{{-1, 1}, {0, -1}, {31, 2}, {0, maxReadSize + 1}}
	for _, item := range tests {
		if err := file.Range2Type(item.offset, item.size, &[]byte{}); err == nil {
			t.Fatalf("range %d/%d was accepted", item.offset, item.size)
		}
	}
}
