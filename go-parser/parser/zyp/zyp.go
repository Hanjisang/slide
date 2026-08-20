package zyp

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
	"math"
	"strconv"
	"strings"
)

const (
	MaxScanTokenSize int64 = 8 * 1024
	JpegBlockSize          = 256
	LayerTotal             = 10
)

var whiteBlock = utils.GetWhiteBlock(JpegBlockSize, JpegBlockSize)
var whiteBlockJpg []byte

func init() {
	buffer := bytes.NewBuffer(nil)
	err := jpeg.Encode(buffer, whiteBlock, &jpeg.Options{Quality: utils.JpegQuality})
	if err != nil {
		panic(err)
	}
	whiteBlockJpg = buffer.Bytes()
}

type LevelOffset struct {
	MinX, MaxX int
	MinY, MaxY int
}

type zyp struct {
	streamer.Streamer
	Version         string
	InfoPos         int64    //图像流结束，信息流开始的pos
	Base64ArrPos    [4]int64 //4个base64的位置pos
	Base64InfoPos   int64    //记录base64 4个pos数组 的pos
	VersionPos      int64    //version字符串位置
	Preview         ImgInfo
	SliceInfo       Item
	Head            types.HeaderInfo
	Barcode         ImgInfo
	OriginalPreview ImgInfo
	ImgInfos        map[string]ImgInfo
	LevelOffsets    [LayerTotal]LevelOffset
	ROIRects        [LayerTotal]image.Rectangle
	FileSize        int64
}

func New(fs streamer.Streamer) (*zyp, error) {
	z := &zyp{Streamer: fs}
	if z.GetType() == "file" {
		var err error
		z.FileSize, err = utils.GetFileSizeBigger1M(z.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	} else {
		var err error
		z.FileSize, err = z.Streamer.GetFileSize()
		if err != nil {
			return nil, err
		}
	}

	info, err := z.GetInfo()
	if err != nil {
		return nil, err
	}

	z.Preview, err = info.GetImgInfoByName("Preview")
	if err != nil {
		return nil, err
	}

	z.SliceInfo, err = info.GetItemByName("SliceInfo")
	if err != nil {
		return nil, err
	}
	err = z.SetROIRects()
	if err != nil {
		return nil, err
	}

	z.Barcode, err = info.GetImgInfoByName("Barcode")
	if err != nil {
		return nil, err
	}

	z.OriginalPreview, err = info.GetImgInfoByName("OriginalPreview")
	if err != nil {
		return nil, err
	}
	// 1.10 没有
	if z.Version == "2.01" {
		//这里的子字段的值有可能是空的
		var fluorescence Item
		fluorescence, err = info.GetItem()
		if err != nil {
			return nil, err
		}
		_ = fluorescence
	}

	_, err = info.GetBase64(0)
	if err != nil {
		return nil, err
	}

	//图像数据紧挨着info.GetBase64(0)
	err = z.SetImgInfos(info)
	if err != nil {
		return nil, err
	}

	_, err = info.GetBase64(1)
	if err != nil {
		return nil, err
	}

	//视野信息段紧挨着info.GetBase64(1)
	zoomView, err := info.GetItemByName("视野信息段")
	if err != nil {
		return nil, err
	}
	//zoomView.Show()
	_ = zoomView

	_, err = info.GetBase64(2)
	if err != nil {
		return nil, err
	}

	// 这里是common开头的一些字符串 紧挨着info.GetBase64(2) 也可能没有
	for {
		if info.GetPosition() == info.Base64ArrPos[3] {
			break
		}
		//fmt.Println(info.GetPosition())
		_, err = info.GetItem()
		if err != nil {
			return nil, err
		}
	}

	_, err = info.GetBase64(3)
	if err != nil {
		return nil, err
	}
	//文件结束
	err = z.SetHead()
	if err != nil {
		return nil, err
	}

	log.Println("init", z.GetFileName(), "版本", z.Version)
	return z, nil
}

func (z *zyp) GetRGBA(layer, x, y int) (image.Image, error) {
	key := GetKey(layer, x, y)
	imgInfo := z.ImgInfos[key]
	if imgInfo.IsEmpty() {
		return whiteBlock, nil
	}
	bs := make([]byte, imgInfo.Size)
	err := z.Range2Type(int64(imgInfo.Pos), int64(imgInfo.Size), bs)
	if err != nil {
		return nil, err
	}
	return jpeg.Decode(bytes.NewReader(bs))
}

func (z *zyp) GetMacrograph(w io.Writer) error {
	return z.Range2Writer(int64(z.OriginalPreview.Pos), int64(z.OriginalPreview.Size), w)
}

func (z *zyp) GetLabelInfoPathFunc(w io.Writer) error {
	return z.Range2Writer(int64(z.Barcode.Pos), int64(z.Barcode.Size), w)
}

func (z *zyp) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	return z.Head, nil
}

func (z *zyp) GetDependencies() ([]string, error) {
	return []string{z.GetFileName()}, nil
}

func (z *zyp) GetFileSize() int64 {
	return z.FileSize
}

// GetInfoPosByEndPos 从文件末尾的某个位置往前找
func (z *zyp) GetInfoPosByEndPos() (int64, error) {
	endPos := z.Base64ArrPos[0]
	prefixNum := int64(len(infoFlag) - 1)

	var count int64 = 1
	size := MaxScanTokenSize + prefixNum //每次取这么多的字节
	pos := endPos - MaxScanTokenSize
	for {
		if count == 1 { //第一次
			pos -= prefixNum
		} else {
			pos = endPos - MaxScanTokenSize*count
		}
		data := make([]byte, size)
		err := z.Range2Type(pos, size, data)
		if err != nil {
			return 0, err
		}
		index := bytes.Index(data, infoFlag)
		//fmt.Println(count, index, pos)
		if index != -1 {
			return pos + int64(index) + 2, nil
		}
		count++
	}
}

func (z *zyp) setVersion() error {
	var psp streamer.PosSizePair
	err := z.Range2Type(z.FileSize-8, 8, &psp.Pos)
	if err != nil {
		return err
	}
	psp.Size = z.FileSize - 8 - psp.Pos
	bs := make([]byte, psp.Size)
	err = z.Range2Type(psp.Pos, psp.Size, bs)
	if err != nil {
		return err
	}
	var k int
	if bytes.Equal(bs[:3], infoFlag[2:]) {
		k = 3
	} else {
		k = 0
	}
	if int64(bs[k]*2) != psp.Size-(int64(k)+1) {
		return fmt.Errorf("int64(bs[k]*2) != psp.Size-(int64(k)+1)")
	}
	rt := &utils.Reader2Type{R: bytes.NewReader(bs[k+1:])}
	z.Version, err = rt.GetStringByUint16Len(int(bs[k]))
	if err != nil {
		return err
	}
	z.VersionPos = psp.Pos
	return nil
}

func (z *zyp) setBase64ArrPos() error {
	size := int64(4 * 8)
	z.Base64InfoPos = z.VersionPos - size
	return z.Range2Type(z.Base64InfoPos, size, &z.Base64ArrPos)
}

func (z *zyp) GetInfo() (*Info, error) {
	//先读文件末尾
	err := z.setVersion()
	if err != nil {
		return nil, err
	}

	err = z.setBase64ArrPos()
	if err != nil {
		return nil, err
	}

	z.InfoPos, err = z.GetInfoPosByEndPos()
	if err != nil {
		return nil, err
	}

	size := z.Base64InfoPos - z.InfoPos
	bs := make([]byte, size)
	err = z.Range2Type(z.InfoPos, size, bs)
	if err != nil {
		return nil, err
	}

	return NewInfo(bytes.NewReader(bs), z.InfoPos, z.Base64ArrPos), nil
}

func (z *zyp) SetImgInfos(info *Info) error {
	z.ImgInfos = make(map[string]ImgInfo)

	for i := 0; i < LayerTotal; i++ {
		z.LevelOffsets[i] = LevelOffset{MinX: math.MaxInt, MinY: math.MaxInt}
	}

	for {
		if info.GetPosition() == info.Base64ArrPos[1] {
			return nil
		}
		item, err := info.GetItem()
		if err != nil {
			return err
		}
		split := strings.Split(item.Name, ",")
		keyNum := len(split)
		if keyNum != 5 && keyNum != 4 {
			return nil
		}
		layerStr := split[keyNum-1-2]
		layer, err := strconv.Atoi(layerStr)
		if err != nil {
			return err
		}

		xStr := split[keyNum-1-1]
		yStr := split[keyNum-1-0]
		x, err := strconv.Atoi(xStr)
		if err != nil {
			return err
		}
		if x > z.LevelOffsets[layer].MaxX {
			z.LevelOffsets[layer].MaxX = x
		}
		if x < z.LevelOffsets[layer].MinX {
			z.LevelOffsets[layer].MinX = x
		}
		y, err := strconv.Atoi(yStr)
		if err != nil {
			return err
		}
		if y > z.LevelOffsets[layer].MaxY {
			z.LevelOffsets[layer].MaxY = y
		}
		if y < z.LevelOffsets[layer].MinY {
			z.LevelOffsets[layer].MinY = y
		}
		z.ImgInfos[GetKey(layer, x, y)], err = item.GetImgInfo()
		if err != nil {
			return err
		}
	}
}

func GetKey(layer, x, y int) string {
	return fmt.Sprintf("%d,%d,%d", layer, x, y)
}

func (z *zyp) GetThumbnailImagePathFunc(w io.Writer) error {
	layer := 7
	rect := z.ROIRects[layer]
	return utils.GetROIImg2Writer(rect, JpegBlockSize, JpegBlockSize, w, func(x, y int) (image.Image, error) {
		return z.GetRGBA(layer, x, y)
	})
}

func (z *zyp) GetImage(layer, line, row int, w io.Writer) error {
	realLayer := z.Head.MaxLayer - layer
	if realLayer < 0 || realLayer >= LayerTotal {
		_, err := w.Write(whiteBlockJpg)
		return err
	}
	r := z.ROIRects[realLayer]
	minX := r.Min.X + line*JpegBlockSize
	minY := r.Min.Y + row*JpegBlockSize
	maxX := minX + JpegBlockSize
	maxY := minY + JpegBlockSize

	rect := image.Rect(minX, minY, maxX, maxY)
	return utils.GetROIImg2Writer(rect, JpegBlockSize, JpegBlockSize, w, func(x, y int) (image.Image, error) {
		return z.GetRGBA(realLayer, x, y)
	})
}

func (z *zyp) SetHead() error {
	atof, err := strconv.ParseFloat(z.SliceInfo.Fields["MicrometersPerPixel"], 64)
	if err != nil {
		return err
	}
	capRes := float32(atof)

	atof, err = strconv.ParseFloat(z.SliceInfo.Fields["ScanZoom"], 64)
	if err != nil {
		return err
	}
	scanScale := int(atof)

	split := strings.Split(z.SliceInfo.Fields["SliceSize"], "×")
	if len(split) != 2 {
		return fmt.Errorf("infos[\"SliceSize\"] is not vaild")
	}
	width, err := strconv.Atoi(split[0])
	if err != nil {
		return err
	}
	height, err := strconv.Atoi(split[1])
	if err != nil {
		return err
	}

	z.Head = types.NewHeaderInfo(
		z.GetFileName(),
		0,
		LayerTotal-1,
		height,
		width,
		float32(scanScale),
		float32(scanScale),
		0,
		0,
		capRes,
		JpegBlockSize,
	)

	return nil
}

func (z *zyp) SetROIRects() error {
	minX, err := strconv.Atoi(z.SliceInfo.Fields["ROILeft"])
	if err != nil {
		return err
	}
	minY, err := strconv.Atoi(z.SliceInfo.Fields["ROITop"])
	if err != nil {
		return err
	}
	maxX, err := strconv.Atoi(z.SliceInfo.Fields["ROIRight"])
	if err != nil {
		return err
	}
	maxY, err := strconv.Atoi(z.SliceInfo.Fields["ROIBottom"])
	if err != nil {
		return err
	}

	key := LayerTotal - 1
	z.ROIRects[key] = image.Rect(minX, minY, maxX, maxY)

	for i := 1; i < LayerTotal; i++ {
		delta := int(math.Pow(2, float64(i)))
		k := key - i
		z.ROIRects[k] = image.Rect(z.ROIRects[key].Min.X*delta, z.ROIRects[key].Min.Y*delta, z.ROIRects[key].Max.X*delta, z.ROIRects[key].Max.Y*delta)
		//fmt.Println(i, k, z.ROIRects[k])
	}

	return nil
}
