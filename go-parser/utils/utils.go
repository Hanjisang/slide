package utils

import (
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"io"
	"os"
	"unicode/utf16"

	"github.com/nfnt/resize"
)

const JpegQuality = 88

func GetFileSizeBigger1M(path string) (int64, error) {
	info, err := os.Stat(path)
	if err != nil {
		return 0, err
	}
	if !info.Mode().IsRegular() {
		return 0, errors.New("slide is not a regular file")
	}
	if info.Size() < 1<<20 {
		return 0, errors.New("slide file is smaller than 1 MiB")
	}
	return info.Size(), nil
}

func PathExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func GetBytesFromPath(path string, offset int64, size uint32) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	return GetBytesFromReaderAt(file, offset, size)
}

func GetBytesFromReaderAt(reader io.ReaderAt, offset int64, size uint32) ([]byte, error) {
	if offset < 0 {
		return nil, errors.New("negative read offset")
	}
	data := make([]byte, int(size))
	_, err := reader.ReadAt(data, offset)
	return data, err
}

func GetWhiteBlock(width, height int) *image.RGBA {
	if width <= 0 || height <= 0 {
		width, height = 256, 256
	}
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	draw.Draw(img, img.Bounds(), &image.Uniform{C: color.White}, image.Point{}, draw.Src)
	return img
}

func Image2RGBA(source image.Image) *image.RGBA {
	if rgba, ok := source.(*image.RGBA); ok {
		return rgba
	}
	result := image.NewRGBA(source.Bounds())
	draw.Draw(result, result.Bounds(), source, source.Bounds().Min, draw.Src)
	return result
}

type RangeInfo struct {
	Id     int
	Low    int
	Offset int
}

func GetRangeByRect(rect image.Rectangle, tileWidth, tileHeight int) (int, int, int, int, int, int) {
	return rect.Min.X % tileWidth, rect.Min.Y % tileHeight,
		rect.Min.X / tileWidth, (rect.Max.X - 1) / tileWidth,
		rect.Min.Y / tileHeight, (rect.Max.Y - 1) / tileHeight
}

func GetRangeInfos(firstOffset, low, high, tileSize, total int) []RangeInfo {
	result := make([]RangeInfo, 0, high-low+1)
	remaining := total
	for id := low; id <= high && remaining > 0; id++ {
		start := 0
		if id == low {
			start = firstOffset
		}
		length := tileSize - start
		if length > remaining {
			length = remaining
		}
		result = append(result, RangeInfo{Id: id, Low: start, Offset: length})
		remaining -= length
	}
	return result
}

func GetImg(line, row, sourceW, sourceH, tileW, tileH int, needResize bool, w io.Writer, fetch func(int, int) (image.Image, error)) error {
	scale := 1
	if needResize {
		scale = 2
	}
	request := image.Rect(line*tileW*scale, row*tileH*scale, (line+1)*tileW*scale, (row+1)*tileH*scale)
	canvas := GetWhiteBlock(request.Dx(), request.Dy())
	for y := request.Min.Y / sourceH; y <= (request.Max.Y-1)/sourceH; y++ {
		for x := request.Min.X / sourceW; x <= (request.Max.X-1)/sourceW; x++ {
			source, err := fetch(x, y)
			if err != nil {
				return err
			}
			tileRect := image.Rect(x*sourceW, y*sourceH, (x+1)*sourceW, (y+1)*sourceH)
			intersection := request.Intersect(tileRect)
			if intersection.Empty() {
				continue
			}
			destination := intersection.Sub(request.Min)
			draw.Draw(canvas, destination, source, intersection.Min.Sub(tileRect.Min), draw.Src)
		}
	}
	var output image.Image = canvas
	if needResize {
		output = resize.Resize(uint(tileW), uint(tileH), canvas, resize.Lanczos3)
	}
	return jpeg.Encode(w, output, &jpeg.Options{Quality: JpegQuality})
}

func GetROIImg2Writer(rect image.Rectangle, tileW, tileH int, w io.Writer, fetch func(int, int) (image.Image, error)) error {
	if rect.Empty() || rect.Dx() > 8192 || rect.Dy() > 8192 {
		return errors.New("invalid ROI dimensions")
	}
	canvas := GetWhiteBlock(rect.Dx(), rect.Dy())
	for y := rect.Min.Y / tileH; y <= (rect.Max.Y-1)/tileH; y++ {
		for x := rect.Min.X / tileW; x <= (rect.Max.X-1)/tileW; x++ {
			source, err := fetch(x, y)
			if err != nil {
				return err
			}
			tileRect := image.Rect(x*tileW, y*tileH, (x+1)*tileW, (y+1)*tileH)
			intersection := rect.Intersect(tileRect)
			if !intersection.Empty() {
				draw.Draw(canvas, intersection.Sub(rect.Min), source, intersection.Min.Sub(tileRect.Min), draw.Src)
			}
		}
	}
	return jpeg.Encode(w, canvas, &jpeg.Options{Quality: JpegQuality})
}

type SizedReadSeeker interface {
	io.ReadSeeker
	Len() int
	Size() int64
}

type Reader2Type struct {
	R SizedReadSeeker
}

func (r *Reader2Type) GetBytes(size int) ([]byte, error) {
	if size < 0 || size > 16<<20 {
		return nil, errors.New("invalid metadata read size")
	}
	data := make([]byte, size)
	_, err := io.ReadFull(r.R, data)
	return data, err
}

func (r *Reader2Type) GetStringByUint16Len(length int) (string, error) {
	if length < 0 || length > 1<<20 {
		return "", errors.New("invalid string length")
	}
	data, err := r.GetBytes(length * 2)
	if err != nil {
		return "", err
	}
	values := make([]uint16, length)
	for i := range values {
		values[i] = binary.LittleEndian.Uint16(data[i*2:])
	}
	return string(utf16.Decode(values)), nil
}
