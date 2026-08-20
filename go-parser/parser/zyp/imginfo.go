package zyp

type ImgInfo struct {
	Pos  uint32
	Size uint32
}

func (info *ImgInfo) IsEmpty() bool {
	return info.Size == 0
}
