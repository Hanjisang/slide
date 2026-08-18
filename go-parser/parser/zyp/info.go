package zyp

import (
	"bytes"
	"fmt"
	"imageparser/utils"
	"imageparser/utils/streamer"
	"io"
)

var infoFlag = []byte{0xFF, 0xD9, 0xFF, 0xFE, 0xFF}

// Info 数据流操作类
// base64 0 之后是图像数据段
// base64 1 之后是视野信息段
// base64 2 之后是common段
// base64 3 之后是4*8+version+8的末尾段
type Info struct {
	utils.Reader2Type
	streamer.PosSizePair
	Base64ArrPos [4]int64
	LastPosition int64 //执行最后一次GetItem的起始位置
}

// NewInfo 初始化一个Info
func NewInfo(r *bytes.Reader, pos int64, base64ArrPos [4]int64) *Info {
	return &Info{Reader2Type: utils.Reader2Type{R: r}, PosSizePair: streamer.PosSizePair{Pos: pos, Size: r.Size()}, Base64ArrPos: base64ArrPos}
}

func (info *Info) Scan() error {
	for {
		item, err := info.GetItem()
		if err != nil {
			// 这里可能到文件末尾
			if err == io.EOF {
				fmt.Println("文件末尾")
				return nil
			}
			return err
		}
		if item.Name == "" {
			return fmt.Errorf("item.Name 为空")
		}
		fmt.Println(item)
	}
}

func (info *Info) GetItem() (item Item, err error) {
	item.Name, err = info.GetString()
	if err != nil {
		return
	}
	var bs []byte
	//子字段处理
	bs, err = info.GetBytes(4)
	if err != nil {
		// 这里可能到文件末尾
		if err == io.EOF {
			return item, nil
		}
		return
	}
	if bs[2] == 0 && bs[3] == 0 { //有子字段
		/*if bs[1] != 0 {
			fmt.Println("sub", bs, info.GetPosition())
		}*/
		fieldNum := int(uint16(bs[0]) + uint16(bs[1])<<8)
		item.Fields, err = info.GetFields(fieldNum)
		if err != nil {
			return
		}
		return
	} else { //无子字段
		info.R.Seek(-4, io.SeekCurrent)
		return
	}
}

func (info *Info) GetItemByName(name string) (item Item, err error) {
	item, err = info.GetItem()
	if err != nil {
		return
	}
	if item.Name != name {
		err = fmt.Errorf("item.Name != SliceInfo")
		return
	}
	return
}

func (info *Info) GetImgInfoByName(name string) (ImgInfo, error) {
	item, err := info.GetItemByName(name)
	if err != nil {
		return ImgInfo{}, err
	}
	return item.GetImgInfo()
}

func (info *Info) GetBase64(idx int) (s2 [2]string, err error) {
	if info.GetPosition() != info.Base64ArrPos[idx] {
		return s2, fmt.Errorf("info.GetPostion() %d != info.Base64ArrPos[%d] %d", info.GetPosition(), idx, info.Base64ArrPos[idx])
	}
	//fmt.Printf("发现info.Base64ArrPos[%d]: %d ", idx, info.Base64ArrPos[idx])
	for i := 0; i < 2; i++ {
		s2[i], err = info.GetString()
		if err != nil {
			return
		}
	}
	//fmt.Println(s2)
	return
}

func (info *Info) GetAllBase64() (ss [4][2]string, err error) {
	for k, pos := range info.Base64ArrPos {
		info.R.Seek(pos-info.Pos, io.SeekStart)
		for i := 0; i < 2; i++ {
			ss[k][i], err = info.GetString()
			if err != nil {
				return
			}
		}
	}
	info.R.Seek(0, io.SeekStart)
	return
}

func (info *Info) GetString() (string, error) {
	var bs []byte
	bs, err := info.GetBytes(4)
	if err != nil {
		return "", err
	}
	if bytes.Equal(bs[:3], infoFlag[2:]) {
		if bs[3] == 0 { //有可能是0
			//fmt.Println("bs[3] == 0", bs)
			return "", nil
		}
		return info.GetStringByUint16Len(int(bs[3]))
	} else if bs[1] != 0 && bs[3] != 0 {
		info.R.Seek(-3, io.SeekCurrent)
		return info.GetStringByUint16Len(int(bs[0]))
	}

	return "", fmt.Errorf("f info not Vaild")
}

func (info *Info) GetFields(num int) (map[string]string, error) {
	fields := make(map[string]string, num)
	for i := 0; i < num; i++ {
		var key, value string
		for j := 0; j < 2; j++ {
			str, err := info.GetString()
			if err != nil {
				return nil, err
			}
			//if str == "" { //value 有可能是""
			//	fmt.Println(j%2, str, str)
			//}
			if j%2 == 0 {
				key = str
			} else {
				value = str
			}
		}
		fields[key] = value
	}

	return fields, nil
}

func (info *Info) GetPosition() int64 {
	return info.Pos + (info.Size - int64(info.R.Len()))
}
