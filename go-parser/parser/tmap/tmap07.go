package tmap

import (
	"fmt"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
	"strings"
)

// 40字节 小图
type littleImgInfo07 struct {
	Idx     uint32
	X1      uint32
	Line256 uint32 //前端调用的line
	Row256  uint32 //前端调用的row

	Width  uint32
	Height uint32
	Pos    int64
	//X2     uint32

	Size int64
	//X3   uint32
}

// 32字节 切片图 标签图 缩略图信息
type imgInfo07 struct {
	Width    uint32 //宽
	Height   uint32 //高
	Unknown1 uint32 //未知 24
	Unknown2 uint32 //未知 0 1 2 3 4 层的索引?

	Pos int64 //起点
	//Unknown3 uint32 //未知 0
	Size int64 //长度
	//Unknown4 uint32 //未知 0
}

// 32字节
type layerInfo07 struct {
	Idx        uint32
	LayerFloat float32
	Width      uint32 //宽
	Height     uint32 //高

	RowNum   uint32 // 从0开始 row数量
	LineNum  uint32 // 从0开始 line数量
	Pos      uint32 // 这一层的读取起始地址(相对于文件)
	Position uint32 // 这一层的读取起始地址(相对于imgInfo切片)
}

// 1072字节 0x430
type header07 struct {
	FileSuffix [8]byte
	Unknown1   uint32 //671106560 不变
	CapRes     uint32 //249 20x的也是254 可能得写死掉 无效数据

	Unknown3     uint32 //957368504 不变
	Unknown4     uint32 //7
	LayerTotal   uint32 //8 //有可能是一共有多少层
	LittleImgNum uint32 //9037

	MarkInfoPos uint32   //35279204
	Unknown6    [12]byte //

	Unknown7 [240]byte //

	Unknown8  uint32 //
	Unknown9  uint32 //变化 5343290
	Unknown10 uint32 //
	Unknown11 uint32 //

	ImgInfos [7]imgInfo07 //32*7=224

	Unknown12 [32]byte //

	LayerInfos [16]layerInfo07 //32*10=320

	//Unknown13 [128]byte //
	//Unknown13 [192]byte //
}

func (h header07) GetFileInfo() string {
	return strings.ToLower(fmt.Sprintf("%s", h.FileSuffix[:6]))
}

type tmap07 struct {
	streamer.Streamer
	Head header07
	//Indexes map[string]littleImgInfo07
	LittleImgInfos []littleImgInfo07
	FileSize       int64
}

func NewTmap07(fs streamer.Streamer) (*tmap07, error) {
	tm := &tmap07{Streamer: fs}

	if tm.GetType() == "file" {
		var err error
		tm.FileSize, err = utils.GetFileSizeBigger1M(tm.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}

	//fmt.Println(tm.GetFileName())
	err := tm.Range2Type(0, 1072, &tm.Head)
	if err != nil {
		return nil, err
	}

	if tm.Head.GetFileInfo() != "tmap07" {
		return nil, fmt.Errorf("%s version not match %s", tm.Head.GetFileInfo(), tm.GetFileName())
	}

	tm.LittleImgInfos = make([]littleImgInfo07, tm.Head.LittleImgNum)
	err = tm.Range2Type(int64(tm.Head.LayerInfos[0].Pos), 40*int64(tm.Head.LittleImgNum), tm.LittleImgInfos)
	if err != nil {
		return nil, err
	}

	log.Println("init tmap07", tm.GetFileName())
	return tm, nil
}

func (tm *tmap07) GetFileSize() int64 {
	return tm.FileSize
}

func (tm *tmap07) GetDependencies() ([]string, error) {
	return []string{tm.GetFileName()}, nil
}

func (tm *tmap07) GetImage(layer, line, row int, w io.Writer) error {
	realLayer := int(tm.Head.LayerTotal) - 1 - layer

	if realLayer < 0 || realLayer > int(tm.Head.LayerTotal)-1 {
		return fmt.Errorf("索引超出范围")
	}

	li := tm.Head.LayerInfos[realLayer]

	if row < 0 || row >= int(li.RowNum) || line < 0 || line >= int(li.LineNum) {
		return fmt.Errorf("索引超出范围")
	}

	pos := int(li.Position) + line + row*int(li.LineNum)
	info := tm.LittleImgInfos[pos]
	//fmt.Println(info)
	return tm.Range2Writer(info.Pos, info.Size, w)
}

func (tm *tmap07) GetLabelInfoPathFunc(w io.Writer) error {
	return tm.Range2Writer(tm.Head.ImgInfos[5].Pos, tm.Head.ImgInfos[5].Size, w)
}

func (tm *tmap07) GetMacrograph(w io.Writer) error {
	return tm.Range2Writer(tm.Head.ImgInfos[4].Pos, tm.Head.ImgInfos[4].Size, w)
}

func (tm *tmap07) GetThumbnailImagePathFunc(w io.Writer) error {
	return tm.Range2Writer(tm.Head.ImgInfos[3].Pos, tm.Head.ImgInfos[3].Size, w)
}

func (tm *tmap07) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	li := tm.Head.LayerInfos[0]
	//fmt.Println(li, parser.Head1.CapRes)

	minLayer := 0
	maxLayer := int(tm.Head.LayerTotal) - 1
	scanScale := 0
	capRes := float32(tm.Head.CapRes) / 1000
	blockSize := 256
	//var capRes float32 = 0.25

	if li.LayerFloat == 20 {
		//maxLayer = 12
		capRes = 0.688
		scanScale = 20
	} else if li.LayerFloat == 40 {
		//maxLayer = 13
		capRes = 0.344
		scanScale = 40
	}

	//minLayer = maxLayer - int(parser.Head1.LayerTotal) + 1

	hif := types.NewHeaderInfo(tm.GetFileName(), minLayer, maxLayer, int(li.Height), int(li.Width), float32(scanScale), 10/capRes, 0, 0, capRes, blockSize)
	//fmt.Println(hif)
	return hif, nil
}
