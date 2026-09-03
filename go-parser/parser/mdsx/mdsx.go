package mdsx

import (
	"bytes"
	"encoding/base64"
	"encoding/xml"
	"fmt"
	"golang.org/x/text/encoding/unicode"
	"imageparser/types"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
	"log"
	"regexp"
	"strconv"
	"strings"
)

const layerNumPos int64 = 74 //layerNumPos 层数的pos
const idxStartPos int64 = 84 //索引的的起始位置

type mdsx struct {
	streamer.Streamer
	LayerNum int8
	Head     header
	Head2    types.HeaderInfo
	//Layers [][]littleImgInfo
	LayerInfos     []rc
	LittleImgInfos []littleImgInfo
	FileSize       int64
}

type header struct {
	Idxes      []idx
	LayerInfos []layerInfo
}

type littleImgInfo struct {
	X1   uint8
	X2   uint8
	Pos  uint32
	Size uint32
}

// 16字节
type idx struct {
	Cid uint16
	Id  uint16
	X3  uint16
	X4  uint16

	Pos  uint32
	Size uint32
}

// 14字节
type layerInfo struct {
	Num  uint32
	X3   uint16
	Pos  uint32
	Size uint32
}

func (md *mdsx) GetDependencies() ([]string, error) {
	return []string{md.GetFileName()}, nil
}

func New(fs streamer.Streamer) (*mdsx, error) {
	md := &mdsx{Streamer: fs}
	if md.GetType() == "file" {
		var err error
		md.FileSize, err = utils.GetFileSizeBigger1M(md.GetFileName()) //文件至少大于1M
		if err != nil {
			return nil, err
		}
	}
	err := md.Range2Type(layerNumPos, 1, &md.LayerNum)
	if err != nil {
		return nil, err
	}
	if md.LayerNum < 6 || md.LayerNum > 32 {
		return nil, fmt.Errorf("invalid MDSX layer count %d", md.LayerNum)
	}

	md.Head.Idxes = make([]idx, md.LayerNum)
	err = md.Range2Type(idxStartPos, 16*int64(md.LayerNum), md.Head.Idxes)
	if err != nil {
		return nil, err
	}
	md.Head.LayerInfos = make([]layerInfo, md.LayerNum)

	var width, height, blockSize, scanScale int
	var capRes float32
	//infos := make([]rc, parser.LayerNum - 5)
	for k, idx := range md.Head.Idxes {
		var t layerInfo
		err := md.Range2Type(int64(idx.Pos), 14, &t)
		if err != nil {
			return nil, err
		}
		//fmt.Println(idx, t)
		md.Head.LayerInfos[k] = t

		//处理真实的数据流
		if idx.Cid > 104 {
			if t.Num == 0 || t.Num > 2_000_000 || t.Size == 0 {
				return nil, fmt.Errorf("invalid MDSX image index count")
			}
			l := littleImgInfo{
				Pos:  t.Pos,
				Size: t.Size,
			}
			md.LittleImgInfos = append(md.LittleImgInfos, l)
			if idx.Size > 14 {
				lis := make([]littleImgInfo, t.Num-1)
				err = md.Range2Type(int64(idx.Pos)+14, 10*(int64(t.Num)-1), lis)
				if err != nil {
					return nil, err
				}
				md.LittleImgInfos = append(md.LittleImgInfos, lis...)
			}
		}

		//处理header
		//if idx.Cid == 101 || idx.Cid == 104 {
		if idx.Cid == 104 {
			bs, err := md.GetBytesFromUnicodeToUTF8ByFile(int64(t.Pos), int64(t.Size))
			if err != nil {
				return nil, err
			}
			width, height, blockSize, md.LayerInfos, err = GetBaseInfo1(bs)
			if err != nil {
				return nil, err
			}
		}

		if idx.Cid == 101 {
			bs, err := md.GetBytesFromUnicodeToUTF8ByFile(int64(t.Pos), int64(t.Size))
			if err != nil {
				return nil, err
			}
			scanScale, capRes, err = GetBaseInfo2(bs)
			if err != nil {
				return nil, err
			}
		}
	}
	//fmt.Println(parser.Infos)
	minLayer := 0
	maxLayer := int(md.LayerNum) - 5 - 1
	//fmt.Println(maxLayer, parser.LayerNum)

	md.Head2 = types.NewHeaderInfo(md.GetFileName(), minLayer, maxLayer, height, width, float32(scanScale), 10/capRes, 0, 0, capRes, blockSize)

	startKey := 0
	for k, li := range md.LayerInfos {
		md.LayerInfos[k].StartKey = startKey
		startKey += li.RowsNum * li.ColsNum
		//fmt.Println(k, parser.LayerInfos[k], startKey)
	}
	//fmt.Println(parser.LayerInfos)

	log.Println("init", md.GetFileName())
	return md, nil
}

func (md *mdsx) GetFileSize() int64 {
	return md.FileSize
}

// GetBaseInfo1 获取切片的基本信息1
func GetBaseInfo1(bs []byte) (int, int, int, []rc, error) {
	var width, height, blockSize int
	re := regexp.MustCompile(`<Width type="\d+" value="(\d+)"/><Height type="\d+" value="(\d+)"/><CellWidth type="\d+" value="(\d+)"/>`)
	subMatch := re.FindSubmatch(bs)
	if len(subMatch) != 4 {
		return 0, 0, 0, nil, fmt.Errorf("MDSX metadata is missing dimensions")
	}
	width, err := strconv.Atoi(string(subMatch[1]))
	if err != nil {
		return 0, 0, 0, nil, err
	}
	height, err = strconv.Atoi(string(subMatch[2]))
	if err != nil {
		return 0, 0, 0, nil, err
	}
	blockSize, err = strconv.Atoi(string(subMatch[3]))
	if err != nil {
		return 0, 0, 0, nil, err
	}
	if width <= 0 || height <= 0 || width > 2_000_000 || height > 2_000_000 || blockSize <= 0 || blockSize > 4096 {
		return 0, 0, 0, nil, fmt.Errorf("MDSX metadata dimensions exceed safety limits")
	}

	re2 := regexp.MustCompile(`<Rows type="\d+" value="(\d+)"/><Cols type="\d+" value="(\d+)"/>`)
	subMatch2 := re2.FindAllSubmatch(bs, -1)
	if len(subMatch2) == 0 || len(subMatch2) > 32 {
		return 0, 0, 0, nil, fmt.Errorf("invalid MDSX level metadata")
	}
	//fmt.Println(subMatch2)
	infos := make([]rc, len(subMatch2))
	for k, sm := range subMatch2 {
		var t rc
		t.LayerIndex = k
		t.RowsNum, err = strconv.Atoi(string(sm[1]))
		if err != nil {
			return 0, 0, 0, nil, err
		}
		t.ColsNum, err = strconv.Atoi(string(sm[2]))
		if err != nil {
			return 0, 0, 0, nil, err
		}
		infos[k] = t
	}

	return width, height, blockSize, infos, nil
}

type rc struct {
	LayerIndex int
	StartKey   int
	RowsNum    int
	ColsNum    int
}

// GetBaseInfo2 获取切片的基本信息2
func GetBaseInfo2(bs []byte) (int, float32, error) {
	var scanScale int
	var capRes float32
	re := regexp.MustCompile(`<ScanObjective value="(\d+)"/><Scale value="([^"]+)"/>`)
	subMatch := re.FindSubmatch(bs)
	if len(subMatch) != 3 {
		return 0, 0, fmt.Errorf("MDSX metadata is missing scan scale")
	}
	scanScale, err := strconv.Atoi(string(subMatch[1]))
	if err != nil {
		return 0, 0, err
	}
	f, err := strconv.ParseFloat(string(subMatch[2]), 6)
	if err != nil {
		return 0, 0, err
	}
	capRes = float32(f)
	if scanScale <= 0 || capRes <= 0 {
		return 0, 0, fmt.Errorf("invalid MDSX scan scale")
	}

	return scanScale, capRes, nil
}

func (md *mdsx) GetBytesFromUnicodeToUTF8ByFile(pos, size int64) ([]byte, error) {
	if size < 10 || size > 16<<20 {
		return nil, fmt.Errorf("invalid MDSX XML metadata size %d", size)
	}
	bs64 := make([]byte, size)
	err := md.Range2Type(pos, size, bs64)
	if err != nil {
		return nil, err
	}
	//xmlbs := []byte{
	//	60,0,63,0,120,0,109,0,108,0,//"<?xml"
	//}
	base64xmlbs := []byte{
		80, 65, 65, 47, 65, 72, 103, 65, 98, 81, //PAA/AHgAbQ
	}

	unicodeBs := make([]byte, size)
	if bytes.Equal(base64xmlbs, bs64[:10]) { //base64加密的xml
		var decoded int
		decoded, err = base64.RawStdEncoding.Decode(unicodeBs, bs64)
		unicodeBs = unicodeBs[:decoded]
	} else { //未加密的xml
		unicodeBs = bs64
	}
	decoder := unicode.UTF16(unicode.LittleEndian, unicode.IgnoreBOM).NewDecoder()
	bs, err := decoder.Bytes(unicodeBs)
	if err != nil {
		return nil, err
	}
	//fmt.Println(bs[:10])
	//fmt.Printf("%s\n", bs[:10])
	return bs, nil
}

func showXml(bs []byte) {
	decoder := unicode.UTF16(unicode.LittleEndian, unicode.IgnoreBOM).NewDecoder()
	bs2, _ := decoder.Bytes(bs[:])
	//fmt.Println(string(bs2), err)

	//decoder := unicode.UTF8.NewDecoder()
	//bytes, _ := decoder.Bytes(bs)
	//fmt.Printf("%layerInfo\n", bytes)
	//p := make([]byte, 100000)
	//utf8.EncodeRune(p, rune(bs))
	//r, size := utf8.DecodeRune(bs)
	//fmt.Println(r, size)
	str := string(bs2)
	replace := strings.Replace(str, "encoding=\"unicode\"", "", 1)

	decoder2 := xml.NewDecoder(strings.NewReader(replace))
	for t, err := decoder2.Token(); err == nil; t, err = decoder2.Token() {
		switch token := t.(type) {
		// 处理元素开始（标签）
		case xml.StartElement:
			name := token.Name.Local
			//fmt.Printf("Token name: %layerInfo\n", name)
			for _, attr := range token.Attr {
				//if attr.Name.Local != "value" {
				//	continue
				//}
				attrName := attr.Name.Local
				attrValue := attr.Value
				fmt.Printf("%s ayerInfo %s ayerInfo = %s ayerInfo\n", name, attrName, attrValue)
			}
		// 处理元素结束（标签）
		case xml.EndElement:
			//fmt.Printf("Token of '%layerInfo' end\n", token.Name.Local)
		// 处理字符数据（这里就是元素的文本）
		case xml.CharData:
			//content := string([]byte(token))
			content := string(token)
			fmt.Printf("This is the content: %v\n", content)
		default:
			// ...
		}
	}
}

func (md *mdsx) GetImage(layer, line, row int, w io.Writer) error {
	realLayer := int(md.LayerNum) - 5 - 1 - layer
	if realLayer < 0 || realLayer >= len(md.LayerInfos) || line < 0 || row < 0 {
		return fmt.Errorf("invalid MDSX tile coordinate")
	}
	layerInfo := md.LayerInfos[realLayer]
	pos, err := mdsxTilePosition(layerInfo, line, row)
	if err != nil {
		return err
	}
	//fmt.Println(layerInfo, realLayer, layer)

	//fmt.Println(fromCache.Head1.LayerInfos[5:])

	//layerInfo := fromCache.Head1.LayerInfos[realLayer]
	if pos < 0 || pos >= len(md.LittleImgInfos) {
		return fmt.Errorf("MDSX tile index out of range")
	}
	//pos := layerInfo.StartKey + row + line*layerInfo.ColsNum
	info := md.LittleImgInfos[pos]
	return md.Range2Writer(int64(info.Pos), int64(info.Size), w)
	//return nil, nil
}

func mdsxTilePosition(layerInfo rc, line, row int) (int, error) {
	// XML Rows is the vertical (y) count and Cols is the horizontal (x)
	// count. The public parser API passes line=x and row=y.
	if line < 0 || row < 0 || line >= layerInfo.ColsNum || row >= layerInfo.RowsNum {
		return 0, fmt.Errorf("MDSX tile coordinate out of range")
	}
	return layerInfo.StartKey + line + row*layerInfo.ColsNum, nil
}

func (md *mdsx) GetLabelInfoPathFunc(w io.Writer) error {
	//pos := md.Head.LayerInfos[3].Pos
	//size := md.Head.LayerInfos[3].Size
	return md.Range2Writer(int64(md.Head.LayerInfos[3].Pos), int64(md.Head.LayerInfos[3].Size), w)
	/*label := filepath.Join(filepath.Dir(path), "label.jpg")
	bs, err := ioutil.ReadFile(label)
	if err != nil {
		fromCache, e := md.remember(path)
		if e != nil {
			return nil, e
		}
		pos := fromCache.Head.LayerInfos[3].Pos
		size := fromCache.Head.LayerInfos[3].Size
		return utils.GetBytesFromPath(path, int64(pos), size)
	}
	return bs, nil*/
}

func (md *mdsx) GetMacrograph(w io.Writer) error {
	return md.Range2Writer(int64(md.Head.LayerInfos[2].Pos), int64(md.Head.LayerInfos[2].Size), w)
}

func (md *mdsx) GetThumbnailImagePathFunc(w io.Writer) error {
	k := md.LayerNum - 1
	pos := md.Head.LayerInfos[k].Pos
	size := md.Head.LayerInfos[k].Size
	return md.Range2Writer(int64(pos), int64(size), w)
	/*thumb := filepath.Join(filepath.Dir(md.GetFileName()), "1.jpg")
	bs, err := ioutil.ReadFile(thumb)
	if err != nil {
		k := md.LayerNum - 1
		pos := md.Head.LayerInfos[k].Pos
		size := md.Head.LayerInfos[k].Size
		return md.Range2Writer(int64(pos), int64(size), w)
	}
	return nil*/
}

func (md *mdsx) GetHeaderInfoFunc() (types.HeaderInfo, error) {
	return md.Head2, nil
}
