package dmetrix

import (
	bytes2 "bytes"
	"fmt"
	"golang.org/x/image/bmp"
	"image"
	"image/jpeg"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"io/ioutil"
	"log"
	"os"
)

// 348字节
type dmetrixInfo struct {
	Company     [7]byte //7+1+10+8+4+4+4+8+2+8+8+4+14*20 = 348字节
	IsEncrypted byte
	Device      [10]byte
	Date        int64
	Width       int32
	Height      int32
	Hsize       int32
	TSize       int64
	MaxLayer    uint16
	XLength     float64
	YLength     float64
	Multiple    int32
	Layeridx    [20]dmetrixLayerIndexDisk
}

type dmetrixLayerIndexDisk struct {
	Layer         uint16
	MaxLine       uint32
	MaxRow        uint32
	LayerStartPos uint32
}

type dmetrixImgIndexDisk struct {
	Layer uint16
	Line  uint32
	Row   uint32
	Pos   int64
	Size  uint32
}

type dmetrix struct {
	streamer.Streamer
	Info     dmetrixInfo
	Head     types.HeaderInfo
	Total    int
	ImgLayer [20]types.LayerIndex
	ImgIdx   []types.ImgIndex
	FileSize int64
}

func New(fs streamer.Streamer) (*dmetrix, error) {
	dm := &dmetrix{Streamer: fs}
	/*if fs.GetType() == "file" {
		startInfo, err := os.Stat(dm.GetFileName())
		if err != nil {
			return nil, err
		}
		if startInfo.Size() < 1024*1024 {
			return nil, errors.New(fmt.Sprintf("%s filesize just start %d", dm.GetFileName(), startInfo.Size()))
		}

		time.Sleep(6 * time.Second)

		endInfo, err := os.Stat(dm.GetFileName())
		if err != nil {
			return nil, err
		}

		if startInfo.Size() != endInfo.Size() {
			return nil, errors.New(fmt.Sprintf("%s filesize start change %d != %d", dm.GetFileName(), startInfo.Size(), endInfo.Size()))
		}
		dm.FileSize = startInfo.Size()
	}*/
	if dm.GetType() == "file" {
		var err error
		dm.FileSize, err = utils.GetFileSizeBigger1M(dm.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}
	//isExist, err := utils.PathExists(filepath.Join(filepath.Dir(streamer.GetFileName()), "preview.bmp"))
	//if err != nil {
	//	return nil, err
	//}
	//if !isExist {
	//	return nil, fmt.Errorf("bmp(label thumb)文件不存在")
	//}

	err := dm.Range2Type(0, 348, &dm.Info)
	if err != nil {
		return nil, err
	}
	if dm.Info.MaxLayer == 0 || dm.Info.MaxLayer > 20 || dm.Info.Width <= 0 || dm.Info.Height <= 0 || dm.Info.XLength <= 0 {
		return nil, fmt.Errorf("invalid DMETRIX header")
	}

	var minLayer int
	dm.Total = 2

	for layer, raw := range dm.Info.Layeridx {
		idx := types.LayerIndex{Layer: raw.Layer, LayerStartPos: int32(raw.LayerStartPos), MaxLine: raw.MaxLine, MaxRow: raw.MaxRow}
		if idx.LayerStartPos > 0 {
			idx.LayerStartPos = int32(dm.Total)
			dm.ImgLayer[layer] = idx
			count, err := checkedTileCount(idx.MaxLine, idx.MaxRow)
			if err != nil || count > 2_000_000-dm.Total {
				return nil, fmt.Errorf("DMETRIX tile index count exceeds safety limit")
			}
			dm.Total += count
			if minLayer == 0 {
				minLayer = layer
			}
		}
	}

	//获取到所有的索引 包括标签图 缩略图 和 layer line row 组成的图
	rawIndexes := make([]dmetrixImgIndexDisk, dm.Total)
	indexBytes, ok := checkedByteCount(dm.Total, 22)
	if !ok {
		return nil, fmt.Errorf("DMETRIX INVALID_TILE_INDEX: index byte count overflow")
	}
	err = dm.Range2Type(348, indexBytes, rawIndexes)
	if err != nil {
		return nil, err
	}
	dm.ImgIdx = make([]types.ImgIndex, len(rawIndexes))
	for i, raw := range rawIndexes { dm.ImgIdx[i] = types.ImgIndex{Layer: raw.Layer, Line: raw.Line, Row: raw.Row, Pos: raw.Pos, Size: raw.Size} }

	dm.Head = types.NewHeaderInfo(dm.GetFileName(), minLayer, int(dm.Info.MaxLayer-1), int(dm.Info.Height), int(dm.Info.Width), float32(dm.Info.Multiple), float32(10/dm.Info.XLength), 0, 0, float32(dm.Info.XLength), 256)

	log.Println("init", dm.GetFileName())
	return dm, nil
}

func (dm *dmetrix) GetFileSize() int64 {
	return dm.FileSize
}

func (dm *dmetrix) GetDependencies() ([]string, error) {
	return []string{dm.GetFileName()}, nil
}

// 获取头信息
func (dm *dmetrix) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	return dm.Head, nil
}

// 标签图
func (dm *dmetrix) GetLabelInfoPathFunc(w io.Writer) error {
	return dm.GetImage(-1, 0, 0, w)
	/*dir := filepath.Dir(dm.GetFileName())
	dir += "/Label"

	bytes, e := getLocalImg(dir)
	if e != nil {
		bytes, e := dm.GetImage(-1, 0, 0)
		if e != nil {
			return nil, e
		}
		r, e := setBytesToLocalJpg(dir, bytes)
		if e != nil || !r {
			return nil, e
		}
		return getLocalImg(dir)
	}
	return bytes, nil*/
}

func (dm *dmetrix) GetMacrograph(w io.Writer) error {
	return dm.GetImage(-1, 0, 0, w)
}

// 缩略图
func (dm *dmetrix) GetThumbnailImagePathFunc(w io.Writer) error {
	return dm.GetImage(-2, 0, 0, w)
	/*dir := filepath.Dir(dm.GetFileName())
	dir += "/preview"

	bytes, e := getLocalImg(dir)
	if e != nil {
		bytes, e := dm.GetImage(dm.GetFileName(), -2, 0, 0)
		if e != nil {
			return nil, e
		}
		r, e := setBytesToLocalJpg(dir, bytes)
		if e != nil || !r {
			return nil, e
		}
		return getLocalImg(dir)
	}
	return bytes, nil*/
}

func (dm *dmetrix) GetImage(layer, line, row int, w io.Writer) error {
	position := 0

	if layer == -1 {
		position = 0
	} else if layer == -2 {
		position = 1
	} else {
		nl := dm.ImgLayer[layer]
		if layer > dm.Head.MaxLayer || line > int(nl.MaxLine) || row > int(nl.MaxRow) || layer < 0 || line < 0 || row < 0 {
			return fmt.Errorf("索引超出范围")
		}
		//position = int(nl.LayerStartPos) + (line*(int(nl.RowNum)+1)+row)*sizeOfIndex
	position = int(nl.LayerStartPos) + line*(int(nl.MaxRow)+1) + row
	if position < 0 || position >= len(dm.ImgIdx) {
		return fmt.Errorf("DMETRIX tile index out of range")
	}
		//fmt.Println("索引:", nl, "pos:", position)
	}

	imgIdx := dm.ImgIdx[position]
	//fmt.Println("取到的:", imgIdx)
	//
	/*//这一段太严格了 不需要这样
	if int(imgIdx.Layer) != layer || int(imgIdx.Line) != line || int(imgIdx.Row) != row {
		return fmt.Errorf("索引超出范围(不匹配)")
	}*/
	//fmt.Println(imgIdx, layer, line, row)
	//
	if imgIdx.Pos < 0 || imgIdx.Size == 0 {
		return fmt.Errorf("DMETRIX INVALID_TILE_INDEX: invalid tile offset or length")
	}
	return dm.Range2Writer(imgIdx.Pos, int64(imgIdx.Size), w)
}

func checkedTileCount(maxLine, maxRow uint32) (int, error) {
	cols, rows := uint64(maxLine)+1, uint64(maxRow)+1
	if cols == 0 || rows == 0 || cols > uint64(2_000_000)/rows {
		return 0, fmt.Errorf("tile count out of range: %d x %d", cols, rows)
	}
	return int(cols * rows), nil
}

func checkedByteCount(count, entrySize int) (int64, bool) {
	if count < 0 || entrySize < 0 || uint64(count) > uint64(^uint64(0))/uint64(entrySize) {
		return 0, false
	}
	return int64(count * entrySize), true
}

func isExists(dir string) bool {
	_, e := os.Stat(dir)
	if e != nil {
		return false
	}
	return true
}

func getLocalJpg(dir string) ([]byte, error) {
	file, e := os.Open(dir + ".jpg")
	if e != nil {
		return nil, fmt.Errorf("jpg文件打开失败")
	}
	defer file.Close()

	return ioutil.ReadAll(file)
}

func setLocalBmptoLocalJpg(dir string) (bool, error) {
	file, e := os.Open(dir + ".bmp")
	if e != nil {
		return false, fmt.Errorf("bmp文件打开失败")
	}
	defer file.Close()

	iif, e := bmp.Decode(file)
	if e != nil {
		return false, e
	}

	return setLocalJpgFromImageInterface(dir, iif)
}

func setBytesToLocalJpg(dir string, bytes []byte) (bool, error) {
	reader := bytes2.NewReader(bytes)
	iif, e := bmp.Decode(reader)
	if e != nil {
		return false, e
	}

	return setLocalJpgFromImageInterface(dir, iif)
}

func setLocalJpgFromImageInterface(dir string, iif image.Image) (bool, error) {
	create, e := os.Create(dir + ".jpg")
	if e != nil {
		return false, e
	}
	defer create.Close()

	e = jpeg.Encode(create, iif, &jpeg.Options{Quality: utils.JpegQuality})
	if e != nil {
		return false, e
	}

	return true, nil
}

func getJpgFromLocalBmp(dir string) ([]byte, error) {
	r, e := setLocalBmptoLocalJpg(dir)
	if e != nil {
		return nil, e
	}
	if !r {
		return nil, fmt.Errorf("bmp 转换 jpg 失败")
	}

	return getLocalJpg(dir)
}

func getLocalImg(dir string) ([]byte, error) {
	if isExists(dir + ".jpg") {
		//打开文件
		//fmt.Println(dir + ".jpg 存在")
		return getLocalJpg(dir)
	} else if isExists(dir + ".bmp") {
		//fmt.Println(dir + ".bmp 存在")
		return getJpgFromLocalBmp(dir)
	}
	return nil, fmt.Errorf("jpg bmp 打开失败")
}
