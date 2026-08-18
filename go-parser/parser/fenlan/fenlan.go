package fenlan

import (
	"fmt"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
)

// 100字节
type fenlanInfo struct {
	Version int32 //4+7+1+10+8+4+4+2+2+4+2+2+2+4+22*2=100字节
	//UserInfoAddr uint64
	Company         [7]byte
	IsEncrypted     byte
	Device          [10]byte
	Date            int64
	Width           uint32
	Height          uint32
	MaxLayer        uint16
	MinLayer        uint16
	SinglePixelSize float32
	CeilWidth       uint16
	CeilLength      uint16
	ImageType       int16
	ScanScale       float32
	ImgIdx          [2]types.ImgIndex
	//Layeridx       [3]LayerId
}

type fenlan struct {
	streamer.Streamer
	Info     fenlanInfo
	Head     types.HeaderInfo
	Total    int
	LayerIdx [20]types.LayerIndex
	ImgIdx   []types.ImgIndex
	FileSize int64
}

func New(fs streamer.Streamer) (*fenlan, error) {
	f := &fenlan{Streamer: fs}
	if f.GetType() == "file" {
		var err error
		f.FileSize, err = utils.GetFileSizeBigger1M(f.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}
	//读取info信息 得到有多少index
	err := f.Range2Type(0, 100, &f.Info)
	if err != nil {
		return nil, err
	}
	if f.Info.MinLayer < 0 || f.Info.MinLayer >= 20 || f.Info.Width <= 0 || f.Info.Height <= 0 || f.Info.SinglePixelSize <= 0 {
		return nil, fmt.Errorf("invalid FENLAN header")
	}

	//maxLayer := int(f.Info.MinLayer)+1

	index := make([]types.LayerIndex, f.Info.MinLayer+1)
	//读取具体的index
	size := 14 * (int64(f.Info.MinLayer) + 1)
	err = f.Range2Type(100, size, index)
	if err != nil {
		return nil, err
	}
	//fmt.Println("index = ", index)

	f.Total = 0
	for layer, idx := range index {
		idx.LayerStartPos = int32(f.Total)
		f.LayerIdx[int(f.Info.MinLayer)-layer] = idx
		f.Total += int(idx.MaxLine * idx.MaxRow)
		if f.Total < 0 || f.Total > 2_000_000 {
			return nil, fmt.Errorf("FENLAN tile index count exceeds safety limit")
		}
	}
	//fmt.Println("f.LayerId = ", f.LayerId)

	f.ImgIdx = make([]types.ImgIndex, f.Total)
	err = f.Range2Type(100+size, 22*int64(f.Total), f.ImgIdx)
	if err != nil {
		return nil, err
	}

	f.Head = types.NewHeaderInfo(f.GetFileName(), 0, int(f.Info.MinLayer), int(f.Info.Height), int(f.Info.Width), f.Info.ScanScale, 10/f.Info.SinglePixelSize, 0, 0, f.Info.SinglePixelSize, 256)

	log.Println("init", f.GetFileName())
	return f, nil
}

func (f *fenlan) GetFileSize() int64 {
	return f.FileSize
}

// //获取头信息
func (f *fenlan) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	return f.Head, nil
}

// 标签图 粉蓝的标签是-2
func (f *fenlan) GetLabelInfoPathFunc(w io.Writer) error {
	return f.GetImage(-1, 0, 0, w)
	//return GetImageFromPath(path, 7688, 73797)
}

func (f *fenlan) GetMacrograph(w io.Writer) error {
	return f.GetImage(-1, 0, 0, w)
}

// 缩略图 粉蓝的缩略是-1
func (f *fenlan) GetThumbnailImagePathFunc(w io.Writer) error {
	return f.GetImage(-2, 0, 0, w)
	//return GetImageFromPath(path, 81485, 2928)
}

func (f *fenlan) GetImage(layer, line, row int, w io.Writer) error {
	position := 0

	if layer == -1 {
		return f.Range2Writer(f.Info.ImgIdx[0].Pos, int64(f.Info.ImgIdx[0].Size), w)
	} else if layer == -2 {
		return f.Range2Writer(f.Info.ImgIdx[1].Pos, int64(f.Info.ImgIdx[1].Size), w)
	} else {
		nl := f.LayerIdx[layer]
		if layer > f.Head.MaxLayer || line > int(nl.MaxLine) || row > int(nl.MaxRow) || layer < 0 || line < 0 || row < 0 {
			return fmt.Errorf("索引超出范围")
		}
		//position = int(nl.LayerStartPos) + (line*(int(nl.RowNum)+1)+row)*sizeOfIndex
		//position = int(nl.LayerStartPos) + line*(int(nl.RowNum)+1) + row
		position = int(nl.LayerStartPos) + line + row*(int(nl.MaxLine))
		if position < 0 || position >= len(f.ImgIdx) {
			return fmt.Errorf("FENLAN tile index out of range")
		}
		//fmt.Println("索引:", nl, "pos:", position)
	}

	imgIdx := f.ImgIdx[position]
	//fmt.Println("取到的:", imgIdx)
	//
	if int(imgIdx.Layer) != (f.Head.MaxLayer-layer) || int(imgIdx.Line) != line || int(imgIdx.Row) != row {
		return fmt.Errorf("索引超出范围(不匹配)")
	}
	//
	//pos := int64(p.Info.Hsize) + ImgIdx.Pos
	return f.Range2Writer(imgIdx.Pos, int64(imgIdx.Size), w)
	//return nil, nil
}

func (f *fenlan) GetDependencies() ([]string, error) {
	return []string{f.GetFileName()}, nil
}
