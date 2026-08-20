package streamer

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
)

const maxReadSize int64 = 64 << 20

type Streamer interface {
	GetFileName() string
	GetFileSize() (int64, error)
	GetType() string
	Range2Type(offset, size int64, out any) error
	Range2Writer(offset, size int64, w io.Writer) error
	Range2File(offset, size int64, path string) error
}

type PosSizePair struct {
	Pos  int64
	Size int64
}

type File struct {
	FileName string
	Order    binary.ByteOrder
}

func NewFile(path string, order binary.ByteOrder) Streamer {
	return &File{FileName: filepath.Clean(path), Order: order}
}

func (f *File) GetFileName() string { return f.FileName }
func (f *File) GetType() string     { return "file" }

func (f *File) GetFileSize() (int64, error) {
	info, err := os.Stat(f.FileName)
	if err != nil {
		return 0, err
	}
	if !info.Mode().IsRegular() {
		return 0, errors.New("slide is not a regular file")
	}
	return info.Size(), nil
}

func (f *File) checkedRange(offset, size int64) error {
	if offset < 0 {
		return errors.New("negative read offset")
	}
	if size < 0 {
		return errors.New("negative read size")
	}
	if size > maxReadSize {
		return fmt.Errorf("read size exceeds %d bytes", maxReadSize)
	}
	fileSize, err := f.GetFileSize()
	if err != nil {
		return err
	}
	if offset > fileSize || size > fileSize-offset {
		return fmt.Errorf("read range [%d,%d) exceeds file size %d", offset, offset+size, fileSize)
	}
	return nil
}

func (f *File) read(offset, size int64) ([]byte, error) {
	if err := f.checkedRange(offset, size); err != nil {
		return nil, err
	}
	data := make([]byte, int(size))
	file, err := os.Open(f.FileName)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	if size == 0 {
		return data, nil
	}
	_, err = file.ReadAt(data, offset)
	return data, err
}

func (f *File) Range2Type(offset, size int64, out any) error {
	if out == nil {
		return errors.New("read target is nil")
	}
	data, err := f.read(offset, size)
	if err != nil {
		return err
	}
	value := reflect.ValueOf(out)
	if value.Kind() == reflect.Ptr && value.Elem().Kind() == reflect.Slice && value.Elem().Type().Elem().Kind() == reflect.Uint8 {
		reflect.Copy(value.Elem(), reflect.ValueOf(data))
		return nil
	}
	return binary.Read(bytesReader(data), f.Order, out)
}

func (f *File) Range2Writer(offset, size int64, w io.Writer) error {
	if w == nil {
		return errors.New("writer is nil")
	}
	data, err := f.read(offset, size)
	if err != nil {
		return err
	}
	_, err = w.Write(data)
	return err
}

func (f *File) Range2File(offset, size int64, path string) error {
	data, err := f.read(offset, size)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

type byteReader struct {
	data []byte
	pos  int
}

func bytesReader(data []byte) *byteReader { return &byteReader{data: data} }

func (r *byteReader) Read(p []byte) (int, error) {
	if r.pos >= len(r.data) {
		return 0, io.EOF
	}
	n := copy(p, r.data[r.pos:])
	r.pos += n
	return n, nil
}
