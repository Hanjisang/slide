package kfb

import (
	"fmt"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
)

var layerFloatToLayer = map[float32]int{
	40:       13,
	20:       12,
	10:       11,
	5:        10,
	2.5:      9,
	1.25:     8,
	0.625:    7,
	0.3125:   6,
	0.15625:  5,
	0.078125: 4,
}

type layerInfo struct {
	Layer    int
	MaxLine  int
	MaxRow   int
	Total    int
	Position int64
}

// 64字节 小图信息
type littleImgInfo struct {
	StartFlag  [4]byte //f1 04 ee ee
	Line256    uint32
	Row256     uint32
	Width      uint32
	Height     uint32
	LayerFloat float32
	X5         uint32
	X6         uint32
	Size       uint32
	X7         int64 //
	//X8         uint32
	X9      uint32
	X10     uint32
	X11     uint32
	X12     uint32
	EndFlag [4]byte //ff 04 ee ee
}

// 52字节 切片图 标签图 缩略图信息
type imgInfo struct {
	StartFlag [4]byte  //f1 02 ee ee 03
	Unknown1  uint32   //未知 1
	Height    uint32   //高
	Width     uint32   //宽
	Unknown2  uint32   //未知 3
	Size      uint32   //长度
	Offset    uint32   //偏移 52
	Unknown3  [20]byte //未知 0 0 0 0 0
	EndFlag   [4]byte  //ff 02 ee ee 03
}

// 96字节+80字节 176字节
type header struct {
	StartFlag  [4]byte //F1 01 EE EE
	FileSuffix [4]byte //文件后缀
	Unknown1   uint32  //未知 0
	Version    float32 //不确定 1.6

	LittleImgNum uint32 //小图总数
	Height       uint32 //高
	Width        uint32 //宽
	ScanScale    uint32 //扫描倍率

	ImageType [4]byte //图片格式 JPG
	Unknown2  uint32  //未知 0
	SpendTime uint32
	ScanTime  uint32

	Unknown3     uint32 //未知 0
	SlicePos     uint32 //切片图起始地址 52字节长度 176 172
	LabelPos     uint32 //标签图起始地址 52字节长度
	ThumbnailPos int64  //缩略图起始地址 52字节长度

	//Unknown4         uint32  //未知 0
	LittleImgInfoPos int64 //小图详细信息起始地址(根据前面的LittleImgNum*64字节得到读取长度)
	//Unknown5         uint32  //未知 0
	CapRes float32 //像素比率

	Unknown6  uint32  //未知 0
	Unknown7  uint32  //未知 0
	BlockSize uint32  //小图尺寸 256
	EndFlag   [4]byte //FF 01 EE EE

	Unknown8  [12]byte
	KFPBLId   [20]byte //KFPBL00500105179
	Unknown9  [4]byte  // 8 0 0 0 /4 0 0 0
	Version2  [8]byte  //1.6.0.14
	Unknown11 [36]byte
}

type kfb struct {
	streamer.Streamer
	Head                  header
	MacrographInfo        imgInfo
	BaseAddr              int64
	BaseLittleImgInfoAddr int64
	SliceInfo             imgInfo
	LabelInfo             imgInfo
	ThumbnailInfo         imgInfo
	//Indexes               map[string]smallImgInfo
	ThumbnailInfo2 smallImgInfo
	SmallImgInfos  []smallImgInfo
	LayerInfos     [20]layerInfo
	MaxLayer       int
	MinLayer       int
	FileSize       int64
}

/*func parserInitFirst(r io.ReadSeeker, path string) (*kfb, error) {
	k := &kfb{streamer: streamer}

	err := binary.Read(r, binary.LittleEndian, &k.Head)
	if err != nil {
		return nil, err
	}

	k.LabelInfo, err = getImgInfo(r, int64(k.Head.LabelPos))
	if err != nil {
		return nil, err
	}

	k.BaseAddr = uint64(k.Head.LabelPos + 52 + k.LabelInfo.Size)

	return k, nil
}*/

func New(fs streamer.Streamer) (*kfb, error) {
	k := &kfb{Streamer: fs}
	if k.GetType() == "file" {
		var err error
		k.FileSize, err = utils.GetFileSizeBigger1M(k.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}
	err := k.Range2Type(0, 176, &k.Head)
	if err != nil {
		return nil, err
	}
	if k.Head.LittleImgNum <= 0 || k.Head.LittleImgNum > 2_000_000 {
		return nil, fmt.Errorf("invalid KFB tile index count %d", k.Head.LittleImgNum)
	}
	if k.Head.BlockSize <= 0 || k.Head.BlockSize > 4096 || k.Head.Width <= 0 || k.Head.Height <= 0 {
		return nil, fmt.Errorf("invalid KFB dimensions or block size")
	}

	err = k.Range2Type(int64(k.Head.SlicePos), 52, &k.MacrographInfo)
	if err != nil {
		return nil, err
	}

	err = k.Range2Type(int64(k.Head.LabelPos), 52, &k.LabelInfo)
	if err != nil {
		return nil, err
	}

	k.BaseAddr = int64(k.Head.LabelPos) + 52 + int64(k.LabelInfo.Size)

	//infos, err := getLittleImgInfos(streamer, int64(k.Head1.LittleImgInfoPos), k.Head1.LittleImgNum-9+1)

	infos := make([]littleImgInfo, k.Head.LittleImgNum)
	err = k.Range2Type(k.Head.LittleImgInfoPos, 64*int64(k.Head.LittleImgNum), infos)
	if err != nil {
		return nil, err
	}

	k.BaseLittleImgInfoAddr = infos[0].X7
	maxLayer := layerFloatToLayer[infos[0].LayerFloat]
	if maxLayer < 0 || maxLayer >= len(k.LayerInfos) {
		return nil, fmt.Errorf("unsupported KFB layer scale %v", infos[0].LayerFloat)
	}
	//minLayer := layerFloatToLayer[infos[len(infos)-1].LayerFloat]
	total := 0
	temp := make(map[string]smallImgInfo)
	var layerInfos [20]layerInfo
	for _, info := range infos {
		if layer, ok := layerFloatToLayer[info.LayerFloat]; ok {
			if layer < 0 || layer >= len(layerInfos) {
				return nil, fmt.Errorf("KFB layer %d exceeds safety limit", layer)
			}
			line := int(info.Line256 / k.Head.BlockSize)
			row := int(info.Row256 / k.Head.BlockSize)
			key := fmt.Sprintf("%v_%v_%v", layer, line, row)
			var t smallImgInfo
			t.Pos = k.BaseAddr + (info.X7 - k.BaseLittleImgInfoAddr)
			t.Size = info.Size
			temp[key] = t
			total++
			if line > layerInfos[layer].MaxLine {
				layerInfos[layer].MaxLine = line
			}
			if row > layerInfos[layer].MaxRow {
				layerInfos[layer].MaxRow = row
			}
		} else {
			//var t smallImgInfo
			//t.Pos = int64(k.BaseAddr + (info.X7 - k.BaseLittleImgInfoAddr))
			//t.Size = info.Size
			//fmt.Println(info.LayerFloat, info.Line256/256, info.Row256/256, info)
			//fmt.Println(info)
		}
		//fmt.Println(info)
	}
	//fmt.Println(layerInfos)
	minLayer := 0
	for k, in := range layerInfos {
		if in.MaxLine != 0 || in.MaxRow != 0 {
			break
		}
		minLayer = k
	}
	//fmt.Println(minLayer)
	count := 0
	for layer := maxLayer; layer >= minLayer; layer-- {
		layerInfos[layer].Layer = layer
		layerInfos[layer].Position = int64(count)
		info := layerInfos[layer]
		count += (info.MaxLine + 1) * (info.MaxRow + 1)
		if count > 2_000_000 {
			return nil, fmt.Errorf("KFB normalized tile count exceeds safety limit")
		}
	}
	//fmt.Println(count)

	i := 0
	k.SmallImgInfos = make([]smallImgInfo, count)
	//num := 0
	for layer := maxLayer; layer >= minLayer; layer-- {
		layerInfos[layer].Layer = layer
		for line := 0; line <= layerInfos[layer].MaxLine; line++ {
			for row := 0; row <= layerInfos[layer].MaxRow; row++ {
				key := fmt.Sprintf("%v_%v_%v", layer, line, row)
				//_ = key
				//_ = temp[key]
				//fmt.Print(key, " + ", i, " = ")
				//fmt.Println(key)
				k.SmallImgInfos[i] = temp[key]
				//if i > 2100 && i < 2400 {
				//	fmt.Println(i, key, layerInfos[layer], temp[key])
				//}
				i++
				//smallImgInfos = append(smallImgInfos, temp[key])
			}
		}
		//info := layerInfos[layer]
		//num += (info.MaxLine+1)*(info.MaxRow+1)
		//fmt.Println(info.Layer, info.MaxLine, info.MaxRow, num)
	}

	k.LayerInfos = layerInfos
	k.MaxLayer = maxLayer
	k.MinLayer = minLayer
	//fmt.Println(smallImgInfos)
	//fmt.Println(len(temp), len(k.SmallImgInfos), total, i, maxLayer, minLayer, k.Head1.LittleImgNum)
	//fmt.Println(layerInfos[minLayer:maxLayer+1])

	k.ThumbnailInfo2, err = k.getThumbnailInfo2(k.Head.ThumbnailPos, k.BaseAddr, k.BaseLittleImgInfoAddr)
	if err != nil {
		return nil, err
	}

	//fmt.Println(infos[len(infos)-1], k.ThumbnailInfo2)
	log.Println("init", k.GetFileName())
	return k, nil
}

func (k *kfb) GetFileSize() int64 {
	return k.FileSize
}

type smallImgInfo struct {
	Pos  int64
	Size uint32
}

func (k *kfb) GetImage(layer, line, row int, w io.Writer) error {
	if layer < k.MinLayer || layer > k.MaxLayer || line < 0 || row < 0 {
		return fmt.Errorf("invalid KFB tile coordinate")
	}
	layerInfo := k.LayerInfos[layer]
	if line > layerInfo.MaxLine || row > layerInfo.MaxRow {
		return fmt.Errorf("KFB tile coordinate out of range")
	}

	pos := layerInfo.Position + int64(line*(layerInfo.MaxRow+1)+row)
	if pos < 0 || pos >= int64(len(k.SmallImgInfos)) {
		return fmt.Errorf("KFB tile index out of range")
	}
	info := k.SmallImgInfos[pos]
	//fmt.Println(fromCache.ImageParserTime.GetLastUseTime())
	if info.Size == 0 {
		return fmt.Errorf("image size = 0")
	}

	//k.Range2Writer(info.Pos)
	return k.Range2Writer(info.Pos, int64(info.Size), w)
	//return nil, fmt.Errorf("get nothing")
}

//func (parser kfb) GetImage2(path string, layer, line, row int) ([]byte, error) {
//	fromCache, e := parser.remember(path)
//	if e != nil {
//		return nil, e
//	}
//
//	key := fmt.Sprintf("%v_%v_%v", layer, line, row)
//	if info, ok := fromCache.Indexes[key]; ok {
//		//pos := int64(fromCache.BaseAddr+(info.X7-fromCache.BaseLittleImgInfoAddr))
//		//fmt.Println(info, pos)
//		return utils.GetBytesFromPath(path, int64(info.Pos), info.Size)
//	}
//
//	return nil, fmt.Errorf("get nothing")
//}

func (k *kfb) GetMacrograph(w io.Writer) error {
	return k.Range2Writer(int64(k.Head.SlicePos+k.MacrographInfo.Offset), int64(k.MacrographInfo.Size), w)
}

func (k *kfb) GetLabelInfoPathFunc(w io.Writer) error {
	return k.Range2Writer(int64(k.Head.LabelPos)+int64(k.LabelInfo.Offset), int64(k.LabelInfo.Size), w)
	/*	fromCache, err := k.get(path)
		if err == nil {
			//fmt.Println("cache label")
			return utils.GetBytesFromPath(path, int64(fromCache.Head.LabelPos+fromCache.LabelInfo.Offset), fromCache.LabelInfo.Size)
		}

		file, err := os.Open(path)
		if err != nil {
			return nil, err
		}
		defer file.Close()

		fromCache, err = parserInitFirst(file, path)
		if err != nil {
			return nil, err
		}

		return utils.GetBytesFromReaderAt(file, int64(fromCache.Head.LabelPos+fromCache.LabelInfo.Offset), fromCache.LabelInfo.Size)*/
}

func (k *kfb) GetThumbnailImagePathFunc(w io.Writer) error {
	return k.Range2Writer(k.ThumbnailInfo2.Pos, int64(k.ThumbnailInfo2.Size), w)
	/*fromCache, err := k.get(path)
	if err == nil {
		//fmt.Println("cache thumbnail")
		return utils.GetBytesFromPath(path, int64(fromCache.ThumbnailInfo2.Pos), fromCache.ThumbnailInfo2.Size)
	}

	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	fromCache, err = parserInitFirst(file, path)
	if err != nil {
		return nil, err
	}
	infos, err := getLittleImgInfos(file, int64(fromCache.Head.LittleImgInfoPos), 1)
	if err != nil {
		return nil, err
	}
	fromCache.BaseLittleImgInfoAddr = infos[0].X7

	fromCache.ThumbnailInfo2, err = getThumbnailInfo2(file, uint64(fromCache.Head.ThumbnailPos), fromCache.BaseAddr, fromCache.BaseLittleImgInfoAddr)
	if err != nil {
		return nil, err
	}

	return utils.GetBytesFromReaderAt(file, int64(fromCache.ThumbnailInfo2.Pos), fromCache.ThumbnailInfo2.Size)*/
}

func (k *kfb) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	minLayer := k.MinLayer
	maxLayer := k.MaxLayer
	//if fromCache.Head1.ScanScale == 20 {
	//	maxLayer = 12
	//} else if fromCache.Head1.ScanScale == 40 {
	//	maxLayer = 13
	//}
	return types.NewHeaderInfo(k.GetFileName(), minLayer, maxLayer, int(k.Head.Height), int(k.Head.Width), float32(k.Head.ScanScale), 10/k.Head.CapRes, float32(k.Head.SpendTime), float64(k.Head.ScanTime), k.Head.CapRes, int(k.Head.BlockSize)), nil
}

func (k *kfb) GetDependencies() ([]string, error) {
	return []string{k.GetFileName()}, nil
}

func (k *kfb) getThumbnailInfo2(thumbnailPos, baseAddr, baseLittleImgInfoAddr int64) (smallImgInfo, error) {
	thumbnailInfo, err := k.getThumbnail(thumbnailPos, 9)
	if err != nil {
		return smallImgInfo{}, err
	}
	var t smallImgInfo
	t.Pos = baseAddr + (thumbnailInfo.X7 - baseLittleImgInfoAddr)
	t.Size = thumbnailInfo.Size

	return t, nil
}

func (k *kfb) getThumbnail(offset int64, num int) (littleImgInfo, error) {
	newOffset := offset - 64*int64(num)
	//infos, err := getLittleImgInfos(r, newOffset, 1)
	infos := make([]littleImgInfo, num)
	err := k.Range2Type(newOffset, 64*int64(num), infos)
	if err != nil {
		return littleImgInfo{}, err
	}
	key := 0
	for i := num - 1; i >= 0; i-- {
		if infos[i].Row256 != 0 || infos[i].Line256 != 0 {
			break
		}
		key = i
	}

	return infos[key], nil
}
