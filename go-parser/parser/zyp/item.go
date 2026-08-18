package zyp

import (
	"fmt"
	"strconv"
)

type Item struct {
	Name   string
	Fields map[string]string
}

func (it *Item) Show() {
	fmt.Println("Name:", it.Name, "FieldsNum:", len(it.Fields))
	for k, v := range it.Fields {
		fmt.Println(k, ":", v)
	}
}

func (it *Item) GetImgInfo() (ii ImgInfo, err error) {
	var atoi int64
	atoi, err = strconv.ParseInt(it.Fields["StartPosition"], 10, 64)
	if err != nil {
		return
	}
	ii.Pos = uint32(atoi)
	atoi, err = strconv.ParseInt(it.Fields["DataLength"], 10, 64)
	if err != nil {
		return
	}
	ii.Size = uint32(atoi)
	return
}
