package tmap

import (
	"bytes"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
	"math"

	"github.com/nfnt/resize"
)

const (
	JpegBlockSize = 256
)

var (
	White256 = utils.GetWhiteBlock(JpegBlockSize, JpegBlockSize)
	White512 = utils.GetWhiteBlock(JpegBlockSize*2, JpegBlockSize*2)
)

type CameraPic struct {
	Rect image.Rectangle
	//SliceWidth  uint16
	//SliceHeight uint16
	Slices []ImgInfo06
}

func (cp *CameraPic) GetImg(fs streamer.Streamer, x, y, ow int) (image.Image, error) {
	xNum := cp.Rect.Dx() / ow
	info := cp.Slices[y*xNum+x]
	bs := make([]byte, info.Size)
	err := fs.Range2Type(int64(info.Pos), int64(info.Size), bs)
	if err != nil {
		return nil, err
	}
	//fs.Range2File(int64(info.Pos), int64(info.Size), fmt.Sprintf("%d_%d.jpg", x, y))
	return jpeg.Decode(bytes.NewReader(bs))
}

func (cp *CameraPic) Draw(fs streamer.Streamer, m *image.RGBA, ow, oh int) error {
	it := cp.Rect.Intersect(m.Bounds())
	//fmt.Println("整体部分：", m.Bounds())
	//fmt.Println("相交的部分：", it, it.Size())
	realIt := it.Sub(cp.Rect.Min)
	//fmt.Println("对于这张照片的位置：", realIt)
	//fmt.Println(utils.GetRangeByRect(realIt, ow, oh))
	posX, posY, xl, xh, yl, yh := utils.GetRangeByRect(realIt, ow, oh)
	xris := utils.GetRangeInfos(posX, xl, xh, ow, realIt.Dx())
	yris := utils.GetRangeInfos(posY, yl, yh, oh, realIt.Dy())
	//fmt.Println(xris, yris)

	var yOffset int
	for _, yri := range yris {
		var xOffset int
		for _, xri := range xris {
			src, err := cp.GetImg(fs, xri.Id, yri.Id, ow)
			if err != nil {
				return err
			}
			//fmt.Println(image.Pt(xOffset, yOffset), it.Add(image.Pt(xOffset, yOffset)), xri.Low, yri.Low)
			draw.Draw(m, it.Add(image.Pt(xOffset, yOffset)), src, image.Pt(xri.Low, yri.Low), draw.Src)
			xOffset += xri.Offset
		}
		yOffset += yri.Offset
	}

	return nil
}

//var DefaultBlock = utils.GetWhiteBlock(JpegBlockSize, JpegBlockSize)
//var originBlock image.Image

func NewTmap06(fs streamer.Streamer) (*tmap06, error) {
	tm := &tmap06{Streamer: fs}

	if tm.GetType() == "file" {
		var err error
		tm.FileSize, err = utils.GetFileSizeBigger1M(tm.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}

	err := tm.Range2Type(0, 192, &tm.Head)
	if err != nil {
		return nil, err
	}
	h := tm.Head
	if h.LayerNum == 0 || h.LayerNum > 16 || h.Total == 0 || uint64(h.Total) > 2_000_000 || uint64(h.B20Num) > 2_000_000 || h.XNum == 0 || h.YNum == 0 || h.Width == 0 || h.Height == 0 || h.WidthImageBlockSize == 0 || h.HeightImageBlockSize == 0 {
		return nil, fmt.Errorf("invalid TMAP06 header")
	}
	//tm.ShowB24()
	//fmt.Printf("%+v\n", h)
	//return tm, nil

	if h.ScanScale != 40 && h.ScanScale != 20 {
		return nil, fmt.Errorf("tmap06 %s ScanScale != 40 and != 20", tm.GetFileName())
	}

	tm.WhiteBlock = utils.GetWhiteBlock(int(h.WidthImageBlockSize), int(h.HeightImageBlockSize))

	blocks := make([]B308, h.Total)
	err = tm.Range2Type(192, int64(h.Total*308), blocks)
	if err != nil {
		return nil, err
	}
	//for i, block := range blocks {
	//	fmt.Println(i, block)
	//}

	infos := make([][]ImgInfo06, h.LayerNum*2)
	pairs := make([]XYTotalPairs06, h.LayerNum)
	cameraPics := make([][]CameraPic, h.LayerNum)
	if h.DS == 4 {
		//if h.Is17 == 1 { Is17 有bug DS==4的时候也有可能为0
		//if h.LayerNum == 3 {
		infos[0] = make([]ImgInfo06, h.Total*uint32(h.DS*h.DS))
		infos[1] = make([]ImgInfo06, h.Total)
		infos[2] = make([]ImgInfo06, h.B20Num)
		pairs[0] = XYTotalPairs06{int(h.XNum * uint16(h.DS)), int(h.YNum * uint16(h.DS))}
		pairs[1] = XYTotalPairs06{int(h.XNum), int(h.YNum)}
		//} else {
		//	return nil, fmt.Errorf("h.DS = 4 时 h.LayerNum != 3")
		//}
		//} else {
		//	return nil, fmt.Errorf("h.DS = 4 时 h.Is17 != 1")
		//}
	} else if h.DS == 2 {
		//if h.Is17 == 0 {
		infos[0] = make([]ImgInfo06, h.Total*16)
		infos[1] = make([]ImgInfo06, h.Total*4)
		infos[2] = make([]ImgInfo06, h.Total)
		pairs[0] = XYTotalPairs06{int(h.XNum * uint16(h.DS*h.DS)), int(h.YNum * uint16(h.DS*h.DS))}
		pairs[1] = XYTotalPairs06{int(h.XNum * uint16(h.DS)), int(h.YNum * uint16(h.DS))}
		pairs[2] = XYTotalPairs06{int(h.XNum), int(h.YNum)}
		//} else {
		//	return nil, fmt.Errorf("h.DS = 2 时 h.Is17 != 0")
		//}
	} else {
		return nil, fmt.Errorf("h.DS != 2 && h.DS != 4")
	}

	yTotal := int(h.YNum)
	xTotal := int(h.XNum)
	//var rects []image.Rectangle
	for y := 0; y < yTotal; y++ {
		for x := 0; x < xTotal; x++ {
			if y*xTotal+x >= len(blocks) {
				return nil, fmt.Errorf("TMAP06 block index out of range")
			}
			block := blocks[y*xTotal+x]
			if block.X == 65535 && block.Y == 65535 {
				continue
			}

			//fmt.Printf("%+v\n", block)
			var slices [][]ImgInfo06
			var levels []byte
			for _, imgInfo := range block.B288 {
				if imgInfo.Pos == 0 || imgInfo.Size == 0 {
					continue
				}
				if !bytes.Contains(levels, []byte{imgInfo.Level}) {
					levels = append(levels, imgInfo.Level)
					var p []ImgInfo06
					slices = append(slices, p)
				}

				slices[imgInfo.Level] = append(slices[imgInfo.Level], ImgInfo06{imgInfo.Pos, imgInfo.Size})

				factor := 4 / math.Pow(float64(h.DS), float64(imgInfo.Level))

				xx := int(float64(x) * factor)
				yy := int(float64(y) * factor)
				xxTotal := int(float64(xTotal) * factor)

				key := (yy+int(imgInfo.Y))*xxTotal + xx + int(imgInfo.X)
				//fmt.Println(imgInfo, key, xxTotal, factor, math.Pow(float64(h.DS), float64(imgInfo.Level)))
				infos[imgInfo.Level][key] = ImgInfo06{imgInfo.Pos, imgInfo.Size}
				//fmt.Printf("\"http://127.0.0.1:18080/img?pos=%d&size=%d\",\n", imgInfo.Pos, imgInfo.Size)
			}
			//fmt.Println(len(slices), levels)
			for i := range levels {
				factor := i * int(h.DS)
				if factor == 0 {
					factor = 1
				}
				startX := int(block.OffsetX) / factor
				startY := int(block.OffsetY) / factor
				endX := startX + int(h.CameraWidth)/factor
				endY := startY + int(h.CameraHeight)/factor
				//cameraPics[i] = append(cameraPics[i], CameraPic{image.Rect(startX, startY, endX, endY), h.WidthImageBlockSize, h.HeightImageBlockSize, slices[i]})
				cameraPics[i] = append(cameraPics[i], CameraPic{image.Rect(startX, startY, endX, endY), slices[i]})
			}
		}
	}

	b20s := make([]B20, h.B20Num)
	err = tm.Range2Type(192+int64(h.Total*308), int64(h.B20Num*20), b20s)
	if err != nil {
		return nil, err
	}
	// 获取后面level的 xTotal 和 yTotal
	for _, b20 := range b20s {
		factor := math.Pow(float64(h.DS), float64(b20.Level))
		x := int(float64(b20.Width) / (float64(h.WidthImageBlockSize) * factor))
		y := int(float64(b20.Height) / (float64(h.HeightImageBlockSize) * factor))
		pairs[b20.Level].XTotal = int(math.Max(float64(x+1), float64(pairs[b20.Level].XTotal)))
		pairs[b20.Level].YTotal = int(math.Max(float64(y+1), float64(pairs[b20.Level].YTotal)))
	}

	// 根据获取到的 xTotal 和 yTotal 来设置 每一层 的 tile数量
	for i, pair := range pairs {
		if len(infos[i]) == 0 {
			infos[i] = make([]ImgInfo06, pair.XTotal*pair.YTotal)
		}
		//fmt.Println(i, pair)
	}

	// 给没后面1层或多层的 level切片 设置 每一个tile 的 pos 和 size
	for _, b20 := range b20s {
		factor := math.Pow(float64(h.DS), float64(b20.Level))
		x := int(float64(b20.Width) / (float64(h.WidthImageBlockSize) * factor))
		y := int(float64(b20.Height) / (float64(h.HeightImageBlockSize) * factor))

		key := y*pairs[b20.Level].XTotal + x
		infos[b20.Level][key] = ImgInfo06{b20.Pos, b20.Size}
		//fmt.Printf("%+v\n", b20)
	}

	//这里节约内存
	if tm.Head.DS == 4 {
		infos[0] = nil
		infos[1] = nil
		//log.Println(tm.Head.DS, len(cameraPics), len(cameraPics[2]))
	} else if tm.Head.DS == 2 {
		infos[0] = nil
		infos[1] = nil
		infos[2] = nil
		//log.Println(tm.Head.DS, len(cameraPics), len(cameraPics[3]))
	}

	tm.ImgInfos = infos
	tm.Pairs = pairs
	tm.CameraPics = cameraPics

	//fmt.Println(len(infos), infos[1])
	//fmt.Println(tm.Pairs)

	log.Println("init tmap06", tm.GetFileName())
	return tm, nil
}

type tmap06 struct {
	streamer.Streamer
	WhiteBlock   *image.RGBA
	Head         header06
	FileSize     int64
	CameraPics   [][]CameraPic
	ImgInfos     [][]ImgInfo06
	Pairs        []XYTotalPairs06
	VirtualLayer bool
}

// func (tm *tmap06) GetVirtualImage(layer, line, row int, w io.Writer) error {
// 	var line_0_row_0_bytes = types.NewBufferedJPEGWriter()
// 	var line_0_row_1_bytes = types.NewBufferedJPEGWriter()
// 	var line_1_row_0_bytes = types.NewBufferedJPEGWriter()
// 	var line_1_row_1_bytes = types.NewBufferedJPEGWriter()
// 	defer func() {
// 		line_0_row_0_bytes.Clear()
// 		line_0_row_1_bytes.Clear()
// 		line_1_row_0_bytes.Clear()
// 		line_1_row_1_bytes.Clear()
// 	}()
// 	tm.GetRealImage(layer, line*2, row*2, line_0_row_0_bytes)
// 	tm.GetRealImage(layer, line*2, row*2+1, line_0_row_1_bytes)
// 	tm.GetRealImage(layer, line*2+1, row*2, line_1_row_0_bytes)
// 	tm.GetRealImage(layer, line*2+1, row*2+1, line_1_row_1_bytes)

// 	// Decode the JPEG images
// 	img0, _ := jpeg.Decode(bytes.NewReader(line_0_row_0_bytes.Data))
// 	img1, _ := jpeg.Decode(bytes.NewReader(line_0_row_1_bytes.Data))
// 	img2, _ := jpeg.Decode(bytes.NewReader(line_1_row_0_bytes.Data))
// 	img3, _ := jpeg.Decode(bytes.NewReader(line_1_row_1_bytes.Data))

// 	img0 = imaging.Resize(img0, 128, 128, imaging.Lanczos)
// 	img1 = imaging.Resize(img1, 128, 128, imaging.Lanczos)
// 	img2 = imaging.Resize(img2, 128, 128, imaging.Lanczos)
// 	img3 = imaging.Resize(img3, 128, 128, imaging.Lanczos)

// 	// Create a new blank image with the combined dimensions
// 	newImage := image.NewRGBA(image.Rect(0, 0, 256, 256)) // Assuming the images are 256x256

// 	// Draw the images onto the new image
// 	draw.Draw(newImage, img0.Bounds(), img0, image.Point{0, 0}, draw.Src)
// 	draw.Draw(newImage, img1.Bounds().Add(image.Point{0, 128}), img1, image.Point{0, 0}, draw.Src)
// 	draw.Draw(newImage, img2.Bounds().Add(image.Point{128, 0}), img2, image.Point{0, 0}, draw.Src)
// 	draw.Draw(newImage, img3.Bounds().Add(image.Point{128, 128}), img3, image.Point{0, 0}, draw.Src)

// 	jpeg.Encode(w, newImage, &jpeg.Options{Quality: utils.JpegQuality})
// 	return nil
// }

func (tm *tmap06) GetImage(layer, line, row int, w io.Writer) error {

	var realLayer int
	var needResize bool

	if tm.VirtualLayer && layer == 0 {
		realLayer = int(tm.Head.LayerNum*2-1) - layer
		needResize = true
		if tm.Head.DS == 4 {
			realLayer /= 2
		}
	} else {
		if tm.VirtualLayer {
			layer--
		}
		if tm.Head.DS == 4 {
			realLayer = int(tm.Head.LayerNum*2-1) - 1 - layer
			//fmt.Println(realLayer)
			if realLayer%2 == 1 {
				needResize = true
			}
			realLayer /= 2
		} else {
			realLayer = int(tm.Head.LayerNum) - 1 - layer
		}
	}
	fmt.Printf("GetImage layer:%d line:%d row:%d \n", layer, line, row)

	//fmt.Println(layer, realLayer, needResize, len(tm.ImgInfos))
	//这里需要从多张图片中获取截图
	if (tm.Head.DS == 4 && realLayer <= 1) || (tm.Head.DS == 2 && realLayer <= 2) {
		return tm.CameraToWriter(line, row, realLayer, needResize, w)
	}

	return utils.GetImg(line, row, int(tm.Head.WidthImageBlockSize), int(tm.Head.HeightImageBlockSize), JpegBlockSize, JpegBlockSize, needResize, w, func(x, y int) (image.Image, error) {
		if x >= tm.Pairs[realLayer].XTotal || y >= tm.Pairs[realLayer].YTotal {
			return tm.WhiteBlock, nil
		}
		key := y*tm.Pairs[realLayer].XTotal + x
		imgInfo := tm.ImgInfos[realLayer][key]
		if imgInfo.Pos == 0 || imgInfo.Size == 0 {
			return tm.WhiteBlock, nil
		}
		bs := make([]byte, imgInfo.Size)
		err := tm.Range2Type(int64(imgInfo.Pos), int64(imgInfo.Size), bs)
		if err != nil {
			return nil, err
		}
		return jpeg.Decode(bytes.NewBuffer(bs))
	})

}

func (tm *tmap06) GetRealImage(layer, line, row int, w io.Writer) error {
	fmt.Printf("GetRealImage layer:%d line:%d row:%d \n", layer, line, row)

	var realLayer int
	var needResize bool

	if tm.Head.DS == 4 {
		realLayer = int(tm.Head.LayerNum*2-1) - 1 - layer
		//fmt.Println(realLayer)
		if realLayer%2 == 1 {
			needResize = true
		}
		realLayer /= 2
	} else {
		realLayer = int(tm.Head.LayerNum) - 1 - layer
	}
	//fmt.Println(layer, realLayer, needResize, len(tm.ImgInfos))
	//这里需要从多张图片中获取截图
	if (tm.Head.DS == 4 && realLayer <= 1) || (tm.Head.DS == 2 && realLayer <= 2) {
		return tm.CameraToWriter(line, row, realLayer, needResize, w)
	}

	return utils.GetImg(line, row, int(tm.Head.WidthImageBlockSize), int(tm.Head.HeightImageBlockSize), JpegBlockSize, JpegBlockSize, needResize, w, func(x, y int) (image.Image, error) {
		if x >= tm.Pairs[realLayer].XTotal || y >= tm.Pairs[realLayer].YTotal {
			return tm.WhiteBlock, nil
		}
		key := y*tm.Pairs[realLayer].XTotal + x
		imgInfo := tm.ImgInfos[realLayer][key]
		if imgInfo.Pos == 0 || imgInfo.Size == 0 {
			return tm.WhiteBlock, nil
		}
		bs := make([]byte, imgInfo.Size)
		err := tm.Range2Type(int64(imgInfo.Pos), int64(imgInfo.Size), bs)
		if err != nil {
			return nil, err
		}
		return jpeg.Decode(bytes.NewBuffer(bs))
	})
}

func (tm *tmap06) CameraToWriter(x, y, layer int, needResize bool, wr io.Writer) error {
	fmt.Printf("CameraToWriter layer:%d line:%d row:%d resize: %v \n", layer, x, y, needResize)

	ow := int(tm.Head.WidthImageBlockSize)
	oh := int(tm.Head.HeightImageBlockSize)
	w := JpegBlockSize
	h := JpegBlockSize
	if needResize {
		w *= 2
		h *= 2
	}
	startX := x * w
	startY := y * h
	rect := image.Rect(startX, startY, startX+w, startY+h)
	m := image.NewRGBA(rect)
	if needResize {
		copy(m.Pix, White512.Pix)
	} else {
		copy(m.Pix, White256.Pix)
	}

	for _, cp := range tm.CameraPics[layer] {
		if cp.Rect.Overlaps(rect) {
			cp.Draw(tm.Streamer, m, ow, oh)
			if m.Rect.In(cp.Rect) {
				//fmt.Println("skip")
				break
			}
		}
	}
	if needResize {
		resizeImg := resize.Resize(uint(w/2), uint(h/2), m, resize.Lanczos3)
		//fmt.Println(needResize, layer)
		return jpeg.Encode(wr, resizeImg, &jpeg.Options{Quality: utils.JpegQuality})
	}

	return jpeg.Encode(wr, m, &jpeg.Options{Quality: utils.JpegQuality})
}

func (tm *tmap06) GetLabelInfoPathFunc(w io.Writer) error {
	var b24 B24
	err := tm.Range2Type(int64(tm.Head.MicroPos), 24, &b24)
	if err != nil {
		return err
	}
	bs := make([]byte, tm.Head.MicroSize-24)
	err = tm.Range2Type(int64(tm.Head.MicroPos+24), int64(tm.Head.MicroSize-24), bs)
	if err != nil {
		return err
	}
	macro, err := jpeg.Decode(bytes.NewReader(bs))

	if err != nil {
		return err
	}
	// label := image.NewRGBA(image.Rect(0, 0, int(b24.Width / 2), int(b24.Height)))
	label := image.NewRGBA(image.Rect(0, 0, int(b24.Height), int(b24.Height)))
	draw.Draw(label, label.Bounds(), macro, image.Pt(0, 0), draw.Src)
	return jpeg.Encode(w, label, &jpeg.Options{Quality: utils.JpegQuality})
	//return tm.Range2Writer(int64(tm.Head.BigThumbPos), int64(tm.Head.BigThumbPos), w)
	//return tm.Range2Writer(int64(tm.Head.ThumbPos+24), int64(tm.Head.ThumbSize-24), w)
}

func (tm *tmap06) GetThumbnailImagePathFunc(w io.Writer) error {
	return tm.Range2Writer(int64(tm.Head.ThumbPos+24), int64(tm.Head.ThumbSize-24), w)
}

// 24个字节
type B24 struct {
	//Level byte //从0开始
	//X     byte
	//Y     byte
	//U     byte //0
	//Pos   uint32
	//Size  uint32
	U1     uint32
	Width  uint32
	Height uint32
	U2     [2]uint16
	X      [2]uint32
}

func (tm *tmap06) ShowB24() error {
	var b24 B24
	err := tm.Range2Type(int64(tm.Head.MicroPos), 24, &b24)
	if err != nil {
		return err
	}
	fmt.Println(b24, tm.Head.MicroSize)

	return nil
}

func (tm *tmap06) GetMacrograph(w io.Writer) error {
	return tm.Range2Writer(int64(tm.Head.MicroPos+24), int64(tm.Head.MicroSize-24), w)
}

func (tm *tmap06) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	var capRes float32 = 0
	tm.VirtualLayer = false
	if tm.Head.ScanScale == 20 {
		capRes = 0.688
	} else if tm.Head.ScanScale == 40 {
		capRes = 0.344
	}

	MaxLayer := int(tm.Head.LayerNum - 1)
	if tm.Head.DS == 4 { //DS == 4 时 LayerNum 大多数是3，但也有是4的
		MaxLayer = int(tm.Head.LayerNum*2 - 1 - 1)
	}

	// 层数过少，需要添加虚拟层，这样页面初次加载时加载更少的图片
	if MaxLayer <= 4 {
		MaxLayer++
		tm.VirtualLayer = true
	}

	//log.Println(tm.Head.DS, tm.Head.LayerNum, MaxLayer)
	return types.NewHeaderInfo(
		tm.GetFileName(),
		0,
		MaxLayer,
		int(tm.Head.Height),
		int(tm.Head.Width),
		float32(tm.Head.ScanScale),
		10/capRes,
		0,
		0,
		capRes,
		JpegBlockSize,
	), nil
}

func (tm *tmap06) GetDependencies() ([]string, error) {
	return []string{tm.GetFileName()}, nil
}

func (tm *tmap06) GetFileSize() int64 {
	return tm.FileSize
}

// 192字节
type header06 struct {
	//0-19 8+1+1+2+1+6+1=20
	FileSuffix [8]byte
	U1         byte
	LayerNum   byte // 一共多少层
	U2         uint16
	DS         byte // 层级下采样率 2或4
	U3         byte
	U4         [3]uint16 //2字节

	//20-47 4+2*9+1+1+2+2=28
	Total                uint32 //Total=XNum*YNum
	ScanScale            uint16 //20或40
	XNum                 uint16
	YNum                 uint16
	CameraWidth          uint16 //相机宽度
	CameraHeight         uint16 //相机高度
	WidthImageBlockSize  uint16
	HeightImageBlockSize uint16
	BigThumbWidth        uint16
	BigThumbHeight       uint16
	Is17                 byte //1是17 0是24 Is17 有bug DS==4的时候也有可能为0 弃用
	U5                   byte //5字节
	B20Num               uint16
	U6                   uint16

	//48-68 4*3+8=20
	Width       uint32
	Height      uint32
	BigThumbPos uint32  //大预览图起始位置 大小未找到
	U7          [8]byte //8字节

	//68-111 4+10*4=44
	ThumbPos  uint32    //预览图起始位置 要加24
	U8        [8]uint32 //32字节
	ThumbPos2 uint32    //预览图起始位置 要加24
	MicroPos  uint32    //大图起始位置 要加24

	//112-191 20*4=80
	CommentPos  uint32     //Comment起始位置 有可能是0就是没有
	U9          [5]uint32  //20个字节
	ThumbSize   uint32     //要减24
	MicroSize   uint32     //要减24
	CommentSize uint32     //Comment长度 有可能是0就是没有
	U10         [11]uint32 //44字节
}

// 20字节
type B20 struct {
	U1    byte // 0
	Level byte
	U2    [2]byte
	//U2     uint16
	Width  uint32
	Height uint32
	Pos    uint32
	Size   uint32
}

// 308字节
type B308 struct {
	U1 [8]byte
	X  uint16
	Y  uint16
	//U2   [2]uint32 //图像错位偏移？
	OffsetX uint32
	OffsetY uint32
	B288    [24]B12 //24*12=288
}

// 12个字节
type B12 struct {
	Level byte //从0开始
	X     byte
	Y     byte
	U     byte //0
	Pos   uint32
	Size  uint32
}

type ImgInfo06 struct {
	Pos  uint32
	Size uint32
}

type XYTotalPairs06 struct {
	XTotal int
	YTotal int
}
