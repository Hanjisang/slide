package tmap

import (
	"fmt"
	"imageparser/types"
	"imageparser/utils/streamer"
	"strings"
)

func New(fs streamer.Streamer) (types.ImageParser, error) {
	var bs [6]byte
	err := fs.Range2Type(0, 6, &bs)
	if err != nil {
		return nil, err
	}
	version := strings.ToLower(fmt.Sprintf("%s", bs))
	if version == "tmap07" {
		return NewTmap07(fs)
	} else if version == "tmap06" {
		return NewTmap06(fs)
	} else {
		return nil, fmt.Errorf("not tmap07 or tmap06")
	}
}
