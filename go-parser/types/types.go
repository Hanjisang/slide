package types

import "io"

type ImageParser interface {
	GetFileName() string
	GetFileSize() int64
	GetDependencies() ([]string, error)
	GetHeaderInfoFunc() (HeaderInfo, error)
	GetImage(layer, line, row int, w io.Writer) error
	GetThumbnailImagePathFunc(w io.Writer) error
	GetLabelInfoPathFunc(w io.Writer) error
	GetMacrograph(w io.Writer) error
}

type HeaderInfo struct {
	FileName     string  `json:"fileName"`
	MinLayer     int     `json:"minLayer"`
	MaxLayer     int     `json:"maxLayer"`
	Height       int     `json:"height"`
	Width        int     `json:"width"`
	KhiScanScale float32 `json:"scanScale"`
	Downsample   float32 `json:"downsample"`
	SpendTime    float32 `json:"spendTime"`
	ScanTime     float64 `json:"scanTime"`
	Mpp          float32 `json:"mpp"`
	BlockSize    int     `json:"blockSize"`
}

func NewHeaderInfo(fileName string, minLayer, maxLayer, height, width int, scanScale, downsample, spendTime float32, scanTime float64, mpp float32, blockSize int) HeaderInfo {
	return HeaderInfo{
		FileName: fileName, MinLayer: minLayer, MaxLayer: maxLayer,
		Height: height, Width: width, KhiScanScale: scanScale,
		Downsample: downsample, SpendTime: spendTime, ScanTime: scanTime,
		Mpp: mpp, BlockSize: blockSize,
	}
}

type LayerIndex struct {
	Layer         uint16
	LayerStartPos int32
	MaxLine       uint32
	MaxRow        uint32
}

type ImgIndex struct {
	Pos   int64
	Size  uint32
	Layer uint16
	Line  uint32
	Row   uint32
}

type BufferedJPEGWriter struct {
	Data []byte
}

func NewBufferedJPEGWriter() *BufferedJPEGWriter { return &BufferedJPEGWriter{} }

func (w *BufferedJPEGWriter) Write(data []byte) (int, error) {
	w.Data = append(w.Data, data...)
	return len(data), nil
}
