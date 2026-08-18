package sdpc

import (
	"bytes"
	"errors"
	"fmt"
	"golang.org/x/image/bmp"
	"image"
	"image/jpeg"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
	"math"
	"os"
)

var ErrDecoderRequired = errors.New("DECODER_REQUIRED: SDPC HEVC decoder is not installed")

const (
	maxEncodedImageSize = 64 << 20
	maxTileIndexCount   = 4_000_000
)

func New(fs streamer.Streamer) (*Sdpc, error) {
	sd := &Sdpc{Streamer: fs}
	if sd.GetType() == "file" {
		var err error
		sd.FileSize, err = utils.GetFileSizeBigger1M(sd.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}

	var picHead SqPicHead
	err := sd.Range2Type(0, 156, &picHead)
	if err != nil {
		return nil, err
	}
	//fmt.Printf("%+v\n", picHead)

	if picHead.Flag != 0x5153 {
		return nil, errors.New("picHead.Flag != 0x5153")
	}
	if picHead.PersonInfo <= 0 {
		return nil, errors.New("picHead.PersonInfo <= 0")
	}
	sd.SliceFormat = picHead.SliceFormat
	sd.Scale = picHead.Scale
	sd.FileLayerNum = int(picHead.Hierarchy)
	sd.SliceWidth = int(picHead.SliceWidth)
	sd.SliceHeight = int(picHead.SliceHeight)
	if sd.FileLayerNum <= 0 || sd.FileLayerNum > 16 || sd.SliceWidth <= 0 || sd.SliceWidth > 4096 || sd.SliceHeight <= 0 || sd.SliceHeight > 4096 || picHead.SrcWidth == 0 || picHead.SrcWidth > 2_000_000 || picHead.SrcHeight == 0 || picHead.SrcHeight > 2_000_000 {
		return nil, errors.New("invalid SDPC dimensions or layer count")
	}
	sd.DefaultSlice = utils.GetWhiteBlock(sd.SliceWidth, sd.SliceHeight)

	var personInfo SqPersonInfo
	err = sd.Range2Type(156, 6808, &personInfo)
	if err != nil {
		return nil, err
	}
	//fmt.Printf("%+v\n", personInfo)

	if personInfo.Flag != 0x4950 {
		return nil, errors.New("picHead.Flag != 0x4950")
	}
	//fmt.Println("NextOffset 宏观图", personInfo.NextOffset)

	//ccm等相关信息
	if picHead.ExtraOffset == 0 {
		return nil, errors.New("picHead.ExtraOffset == 0")
	}
	err = sd.Range2Type(156+6808, 768, &sd.ExtraInfo)
	if err != nil {
		return nil, err
	}
	if sd.ExtraInfo.Flag != 0x4945 {
		return nil, errors.New("sd.ExtraInfo.Flag != 0x4945")
	}
	sd.ColorCorrector, err = newColorCorrector(sd.ExtraInfo.CcmRgbRate, sd.ExtraInfo.CcmHsvRate, sd.ExtraInfo.CcmGamma, sd.ExtraInfo.Ccm)
	if err != nil {
		return nil, err
	}

	//读取宏观图
	if picHead.Macrograph == 0 {
		return nil, errors.New("picHead.Macrograph == 0")
	}

	//第一张宏观图
	var macrographInfo SqMacrographInfo
	err = sd.Range2Type(personInfo.NextOffset, 123, &macrographInfo)
	if err != nil {
		return nil, err
	}
	if macrographInfo.Flag != 0x494D {
		return nil, errors.New("macrographInfo.Flag != 0x494D")
	}
	//fmt.Printf("%+v\n", macrographInfo)
	//fmt.Printf("第一张宏观图 pos=%d size=%d\n", personInfo.NextOffset+123, macrographInfo.EncodeSize)
	sd.Label = img{personInfo.NextOffset + 123, uint32(macrographInfo.EncodeSize)}
	//log.Println(sd.WriteToFile(personInfo.NextOffset + 123, macrographInfo.EncodeSize, "label.jpg"))
	//第二张宏观图
	var macrographInfo1 SqMacrographInfo
	err = sd.Range2Type(macrographInfo.NextLayerOffset, 123, &macrographInfo1)
	if err != nil {
		return nil, err
	}
	if macrographInfo1.Flag != 0x494D {
		return nil, errors.New("macrographInfo1.Flag != 0x494D")
	}
	//fmt.Printf("%+v\n", macrographInfo1)
	//fmt.Printf("第二张宏观图 pos=%d size=%d\n", macrographInfo.NextLayerOffset+123, macrographInfo1.EncodeSize)

	sd.Macrograph = img{macrographInfo.NextLayerOffset + 123, uint32(macrographInfo1.EncodeSize)}
	//log.Println(sd.WriteToFile(macrographInfo.NextLayerOffset+123, macrographInfo1.EncodeSize, "m1.jpg"))

	//缩略图
	var picInfo SqPicInfo
	err = sd.Range2Type(macrographInfo1.NextLayerOffset, 122, &picInfo)
	if err != nil {
		return nil, err
	}
	if picInfo.Flag != 0x4649 {
		return nil, errors.New("picInfo.Flag != 0x4649")
	}
	//fmt.Printf("%+v\n", picInfo)
	//fmt.Printf("缩略图 pos=%d size=%d\n", macrographInfo1.NextLayerOffset+122, picInfo.LayerSize)
	sd.Thumb = img{macrographInfo1.NextLayerOffset + 122, uint32(picInfo.LayerSize)}
	//log.Println(sd.WriteToFile(macrographInfo1.NextLayerOffset + 122, picInfo.LayerSize, "thumb.jpg"))

	//层级信息
	pos := picInfo.NextLayerOffset
	sd.FileLayerInfo = make([]SqPicInfo, sd.FileLayerNum)
	sd.FileLayer = make([]Layer, sd.FileLayerNum)
	sd.FileImgs = make([][]img, sd.FileLayerNum)

	var totalTileIndexes uint64
	for i := 0; i < sd.FileLayerNum; i++ {
		var layerInfo SqPicInfo
		err = sd.Range2Type(pos, 122, &layerInfo)
		if err != nil {
			return nil, err
		}
		if layerInfo.Flag != 0x4649 {
			return nil, errors.New("layerInfo.Flag != 0x4649")
		}
		if layerInfo.SliceNum == 0 || layerInfo.SliceNum > 2_000_000 || layerInfo.SliceNumX == 0 || layerInfo.SliceNumY == 0 || uint64(layerInfo.SliceNumX)*uint64(layerInfo.SliceNumY) < uint64(layerInfo.SliceNum) {
			return nil, errors.New("invalid SDPC tile index count")
		}
		totalTileIndexes += uint64(layerInfo.SliceNum)
		if totalTileIndexes > maxTileIndexCount {
			return nil, errors.New("SDPC tile index count exceeds limit")
		}
		//fmt.Printf("%d %+v\n", pos, layerInfo)

		var ruler float64
		if i == 0 {
			ruler = picHead.Ruler
		} else {
			ruler = layerInfo.Ruler
		}
		width := uint64(layerInfo.SliceNumX) * uint64(picHead.SliceWidth)
		height := uint64(layerInfo.SliceNumY) * uint64(picHead.SliceHeight)
		if width == 0 || height == 0 || width > 2_000_000 || height > 2_000_000 {
			return nil, errors.New("invalid SDPC layer dimensions")
		}
		w := uint32(width)
		h := uint32(height)
		bx := uint32(math.Ceil(float64(w) / JpegBlockSize))
		by := uint32(math.Ceil(float64(h) / JpegBlockSize))
		sd.FileLayer[i] = Layer{i, layerInfo.SliceNumX, layerInfo.SliceNumY, w, h, bx, by, ruler}

		imgs := make([]img, layerInfo.SliceNum)
		sizes := make([]uint32, layerInfo.SliceNum)
		startPos := pos + 122 + int64(layerInfo.SliceNum)*4
		err = sd.Range2Type(pos+122, int64(layerInfo.SliceNum)*4, &sizes)
		if err != nil {
			return nil, err
		}
		for j, size := range sizes {
			imgs[j] = img{startPos, size}
			startPos += int64(size)
		}
		sd.FileImgs[i] = imgs
		pos = layerInfo.NextLayerOffset

		sd.FileLayerInfo[i] = layerInfo
	}

	if sd.Scale == 0.25 {
		sd.LayerNum = sd.FileLayerNum*2 - 1
		sd.Layer = make([]Layer, sd.LayerNum)
		sd.Imgs = make([][]img, sd.LayerNum)
		for j := 0; j < sd.LayerNum; j++ {
			if j%2 == 0 {
				sd.Layer[j] = sd.FileLayer[j/2]
				sd.Imgs[j] = sd.FileImgs[j/2]
				sd.Layer[j].Idx = j
			} else {
				x := uint32(math.Ceil(float64(sd.Layer[j-1].X) / 2))
				y := uint32(math.Ceil(float64(sd.Layer[j-1].Y) / 2))
				w := x * picHead.SliceWidth
				h := y * picHead.SliceHeight
				bx := uint32(math.Ceil(float64(w) / JpegBlockSize))
				by := uint32(math.Ceil(float64(h) / JpegBlockSize))
				sd.Layer[j] = Layer{j, x, y, w, h, bx, by, math.Pow(2, float64(j)) * sd.Layer[0].Ruler}
				//sd.Imgs[j] = make([]img, 0)
			}
		}
	} else {
		//fmt.Println("sd.Scale =", sd.Scale)
		sd.LayerNum = sd.FileLayerNum
		sd.Layer = sd.FileLayer
		sd.Imgs = sd.FileImgs
	}

	sd.Head = types.NewHeaderInfo(sd.GetFileName(), 0, sd.LayerNum-1, int(picHead.SrcHeight), int(picHead.SrcWidth), float32(picHead.Rate), 10/float32(sd.Layer[0].Ruler), 0, 0, float32(sd.Layer[0].Ruler), JpegBlockSize)

	log.Println("init", sd.GetFileName())
	return sd, nil
}

func (sd *Sdpc) GetRaw(idx, x, y int) ([]byte, bool, error) {
	isDefaultSlice := false
	if idx < 0 || idx >= len(sd.FileLayer) || idx >= len(sd.FileImgs) || x < 0 || y < 0 {
		return nil, false, errors.New("invalid SDPC native tile coordinate")
	}
	layerInfo := sd.FileLayer[idx]
	if x >= int(layerInfo.X) || y >= int(layerInfo.Y) {
		isDefaultSlice = true
		return sd.DefaultSlice.Pix, isDefaultSlice, nil
	}
	pos := x + y*int(layerInfo.X)
	if pos < 0 || pos >= len(sd.FileImgs[idx]) {
		return nil, false, errors.New("SDPC tile index out of range")
	}
	info := sd.FileImgs[idx][pos]
	if info.Size == 0 || uint64(info.Size) > maxEncodedImageSize {
		return nil, false, errors.New("invalid SDPC encoded tile size")
	}

	bs := make([]byte, info.Size)
	err := sd.Range2Type(info.Pos, int64(info.Size), bs)
	if err != nil {
		fmt.Printf("GetRaw err: %v\n", err)
		return nil, isDefaultSlice, err
	}
	return bs, isDefaultSlice, nil

}

func (sd *Sdpc) GetRGBA(idx, x, y int) (image.Image, error) {
	bs, isDefaultSlice, err := sd.GetRaw(idx, x, y)
	if err != nil {
		return nil, err
	}

	if isDefaultSlice {
		return sd.ColorCorrector.Apply(sd.DefaultSlice)
	} else {
		var decoded image.Image
		switch sd.SliceFormat {
		case 0:
			decoded, err = jpeg.Decode(bytes.NewReader(bs))
			if err != nil {
				return nil, err
			}
		case 1:
			decoded, err = bmp.Decode(bytes.NewReader(bs))
			if err != nil {
				return nil, err
			}
		case 4:
			return nil, ErrDecoderRequired
		default:
			return nil, errors.New("only support jpeg bmp hevc")
		}
		if decoded.Bounds().Dx() != sd.SliceWidth || decoded.Bounds().Dy() != sd.SliceHeight {
			return nil, errors.New("SDPC decoded tile dimensions do not match header")
		}
		return sd.ColorCorrector.Apply(decoded)
	}
}

func (sd *Sdpc) GetImage(layer, line, row int, w io.Writer) error {
	if layer >= sd.LayerNum || layer < 0 {
		return errors.New("layer 超出范围")
	}

	realLayer := sd.LayerNum - 1 - layer
	layerInfo := sd.Layer[realLayer]
	if line >= int(layerInfo.Bx) || line < 0 {
		return errors.New("line 超出范围")
	}
	if row >= int(layerInfo.By) || row < 0 {
		return errors.New("row 超出范围")
	}

	//fmt.Printf("layer=%d realLayer=%d ++", layer, realLayer)

	var needResize bool

	if sd.Scale == 0.25 {
		if realLayer%2 == 1 {
			needResize = true
		}
		realLayer /= 2
	}

	return utils.GetImg(line, row, sd.SliceWidth, sd.SliceHeight, JpegBlockSize, JpegBlockSize, needResize, w, func(x, y int) (image.Image, error) {
		return sd.GetRGBAFromParser(realLayer, x, y)
	})
}

//func (sd *sdpc) GetImage2(layer, line, row int, w io.Writer) error {
//	if layer >= sd.LayerNum || layer < 0 {
//		return errors.New("layer 超出范围")
//	}
//
//	realLayer := sd.LayerNum - 1 - layer
//	layerInfo := sd.Layer[realLayer]
//	if line >= int(layerInfo.Bx) || line < 0 {
//		return errors.New("line 超出范围")
//	}
//	if row >= int(layerInfo.By) || row < 0 {
//		return errors.New("row 超出范围")
//	}
//
//	//fmt.Printf("layer=%d realLayer=%d ++", layer, realLayer)
//	width := JpegBlockSize
//	height := JpegBlockSize
//	if sd.Scale == 0.25 {
//		if realLayer%2 == 1 {
//			width *= 2
//			height *= 2
//		}
//		realLayer /= 2
//	}
//
//	//fmt.Printf(" ++ %d\n", realLayer)
//	posX, posY, xl, xh, yl, yh := utils.GetRange(line, row, sd.SliceWidth, sd.SliceHeight, width, height)
//	big := image.NewRGBA(image.Rect(0, 0, sd.SliceWidth*(xh-xl+1), sd.SliceHeight*(yh-yl+1)))
//	//fmt.Printf("posX=%d posY=%d xl=%d xh=%d yl=%d yh=%d rect=%v\n", posX, posY, xl, xh, yl, yh, big.Rect)
//	var y int
//	for j := yl; j <= yh; j++ {
//		var x int
//		for i := xl; i <= xh; i++ {
//			r, err := sd.GetRGBA(realLayer, i, j)
//			if err != nil {
//				return err
//			}
//			draw.Draw(big, r.Bounds().Add(image.Pt(x*sd.SliceWidth, y*sd.SliceHeight)), r, image.ImagePosition{}, draw.Src)
//			x++
//		}
//		y++
//	}
//	small := image.NewRGBA(image.Rect(0, 0, width, height))
//	draw.Draw(small, small.Bounds(), big, image.Pt(posX, posY), draw.Src)
//	if width == JpegBlockSize*2 && height == JpegBlockSize*2 {
//		resizeImg := resize.Resize(JpegBlockSize, JpegBlockSize, small, resize.Lanczos3)
//		return jpeg.Encode(w, resizeImg, nil)
//	}
//
//	return jpeg.Encode(w, small, nil)
//}

func (sd *Sdpc) GetFileSize() int64 {
	return sd.FileSize
}

func (sd *Sdpc) GetLabelInfoPathFunc(w io.Writer) error {
	return sd.Range2Writer(sd.Label.Pos, int64(sd.Label.Size), w)
}

func (sd *Sdpc) GetMacrograph(w io.Writer) error {
	return sd.Range2Writer(sd.Macrograph.Pos, int64(sd.Macrograph.Size), w)
}

func (sd *Sdpc) GetThumbnailImagePathFunc(w io.Writer) error {
	//return sd.Range2Writer(sd.Thumb.Pos, int64(sd.Thumb.Size), w)
	return sd.ToColorCorrectRgba(sd.Thumb.Pos, int64(sd.Thumb.Size), w)
}

func (sd *Sdpc) ToColorCorrectRgba(pos, size int64, w io.Writer) error {
	if size <= 0 || size > maxEncodedImageSize {
		return errors.New("invalid SDPC encoded image size")
	}
	bs := make([]byte, size)
	err := sd.Range2Type(pos, size, bs)
	if err != nil {
		return err
	}

	t, err := bmp.Decode(bytes.NewReader(bs))
	if err != nil {
		t, err = jpeg.Decode(bytes.NewReader(bs))
		if err != nil {
			return err
		}
	}

	im, err := sd.ColorCorrector.Apply(t)
	if err != nil {
		return err
	}

	return jpeg.Encode(w, im, &jpeg.Options{Quality: utils.JpegQuality})
}

// WriteToFile 加入颜色校准
func (sd *Sdpc) WriteToFile(pos, size int64, name string) error {
	file, err := os.Create(name)
	if err != nil {
		return err
	}
	defer file.Close()
	return sd.ToColorCorrectRgba(pos, size, file)
}

func (sd *Sdpc) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	return sd.Head, nil
}

func (sd *Sdpc) GetDependencies() ([]string, error) {
	return []string{sd.GetFileName()}, nil
}

func (sd *Sdpc) GetRGBAFromParser(idx, x, y int) (image.Image, error) {
	return sd.GetRGBA(idx, x, y)
}
