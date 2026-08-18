package kfb

import (
	"encoding/binary"
	"fmt"
	"io"
)

func spy() {
	fmt.Println("开始读取96字节是头信息")
	fmt.Println("开始读取76或80字节，根据头信息的LabelPos是172或176来指定")
	fmt.Println("开始读取52字节 (切片图size信息)")
	fmt.Println("根据读取到的切片图信息读取图片流")
	fmt.Println("开始读取52字节 (label图size信息)")
	fmt.Println("根据读取到的label图信息读取图片流")
	fmt.Println("所有小图图片流")
	fmt.Println("根据读取到的小图数量(头信息)*64字节的小图索引")
	fmt.Println("开始读取52字节 (缩略图size信息)")
	fmt.Println("根据读取到的缩略图信息读取图片流")
}

func getImgInfo(r io.ReadSeeker, offset int64) (imgInfo, error) {
	_, err := r.Seek(offset, 0)
	if err != nil {
		return imgInfo{}, err
	}
	var img imgInfo
	err = binary.Read(r, binary.LittleEndian, &img)
	if err != nil {
		return imgInfo{}, err
	}
	return img, nil
}

func getLittleImgInfos(r io.ReadSeeker, offset int64, littleImgNum uint32) ([]littleImgInfo, error) {
	_, err := r.Seek(offset, 0)
	if err != nil {
		return nil, err
	}

	littleImgInfos := make([]littleImgInfo, littleImgNum)
	err = binary.Read(r, binary.LittleEndian, littleImgInfos)
	if err != nil {
		return nil, err
	}
	return littleImgInfos, nil
}

func showLittleImgInfos(r io.ReadSeeker, offset int64, littleImgNum uint32) {
	infos, err := getLittleImgInfos(r, offset, littleImgNum)
	if err != nil {
		panic(err)
	}
	_ = infos
}
