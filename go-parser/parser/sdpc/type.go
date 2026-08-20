package sdpc

import (
	"image"
	"imageparser/types"
	"imageparser/utils/streamer"
)

const (
	JpegBlockSize = 256
)

type Sdpc struct {
	streamer.Streamer
	LayerNum                 int
	SliceWidth               int
	SliceHeight              int
	DefaultSlice             *image.RGBA //超出范围 补边用的
	Label, Macrograph, Thumb img
	SliceFormat              uint32
	Scale                    float32 //0.5 2倍 0.25 4倍
	Head                     types.HeaderInfo
	FileLayerNum             int
	FileLayerInfo            []SqPicInfo //sdpc原始层级信息
	FileLayer                []Layer     //自定义层级信息
	Layer                    []Layer
	FileImgs                 [][]img
	Imgs                     [][]img
	FileSize                 int64
	ExtraInfo                SqExtraInfo
	ColorCorrector           *ColorCorrector
	Decoder                  Decoder
}

// 7*4+8 = 36
type Layer struct {
	Idx           int
	X, Y          uint32  //层级id 层级x方向切片数量 层级y方向切片数量
	Width, Height uint32  //这一层的总宽度和总高度
	Bx, By        uint32  //按指定JpegBlockSize 的x方向切片数量和y方向切片数量
	Ruler         float64 //微米每像素
}

type img struct {
	Pos  int64
	Size uint32
}

// 30+36+18+20+52=156 OK
type SqPicHead struct {
	Flag     uint16 /* 低8位为字母’S’，高8位为字母’Q ' 0x5153*/
	Version  [16]byte
	HeadSize uint32
	FileSize int64

	Macrograph      uint32 //宏观图,0表示没有图，>= 1则表示有多少张宏观图
	PersonInfo      uint32 //病人信息
	Hierarchy       uint32 /* 层级 */
	SrcWidth        uint32 /* 原始图像大小 */
	SrcHeight       uint32
	SliceWidth      uint32 /* 切片大小 */
	SliceHeight     uint32
	ThumbnailWidth  uint32
	ThumbnailHeight uint32

	/* 色深，即每个像素所占位数(bits per pixel)典型值为：1、4、8、16、24、32 */
	Bpp byte
	/* 压缩质量0-100 */
	Quality byte
	/* 图像色彩空间 */
	//J_COLOR_SPACE colorSpace
	ColorSpace uint32
	/* 缩放比例 */
	Scale float32 //0.5 2倍 0.25 4倍
	Ruler float64 /*比例尺，一个像素对应多大的尺寸，单位为um*/

	/* 预留空间 */
	Rate        uint32   /*扫描倍率*/
	ExtraOffset int64    /*额外信息偏移量，存储着CCM、相机参数，若为0，则表示不存在*/
	TileOffset  int64    /*白细胞图像偏移量，内嵌有白细胞图像，若为0，则表示不存在*/
	SliceFormat uint32   //切片格式 0 jpeg 1 bmp 2 png 3 tiff 4 hevc
	HeadSpace   [48]byte //预留空间
}

// 2+4+64+64+2+64+64+1024+2048+2048+64+64+1024+8+4+4+256=6808 OK
type SqPersonInfo struct {
	Flag                  uint16   /*标志位 低8位为字母’P’，高8位为字母’I ' 0x4950*/
	InfoSize              uint32   //个人信息大小
	PathologyID           [64]byte //病理号
	Name                  [64]byte
	Sex                   byte //性别,1位男，0为女
	Age                   byte
	Departments           [64]byte //科室
	Hospital              [64]byte
	SubmittedSamples      [1024]byte //送检样本信息
	ClinicalDiagnosis     [2048]byte //临床诊断
	PathologicalDiagnosis [2048]byte //病理诊断
	ReportDate            [64]byte   //报告日期
	AttendingDoctor       [64]byte   //主诊医生
	Remark                [1024]byte //备注信息
	NextOffset            int64      //下一个偏移量
	Reversed1             uint32     /* 保留字1，必须设置为0 */
	Reversed2             uint32     /* 保留字2，必须设置为0 */
	Reversed              [256]byte  //保留字节
}

// 2+4+8+20+4+15*4+32+4+20+32+1+4+2+2+128+12+433=768 OK
type SqExtraInfo struct {
	Flag       uint16 /*标志位 低8位为字母’E’，高8位为字母’I ' 0x4945*/
	InfoSize   uint32 //额外信息大小
	NextOffset int64  //下一个偏移量
	/*CCM颜色校准参数*/
	Model      [20]byte      //相机型号
	CcmGamma   float32       //gamma
	CcmRgbRate [3]float32    //rgb比例
	CcmHsvRate [3]float32    //hsv比例
	Ccm        [3][3]float32 //3*3矩阵

	TimeConsuming   [32]byte   //扫描时刻
	ScanTime        uint32     //扫描时间
	StepTime        [10]uint16 //每步耗时
	Serial          [32]byte   //序列号
	FusionLayer     byte       //融合层数
	Step            float32    //步进
	FocusPoint      uint16     //对焦点
	ValidFocusPoint uint16     //有效对焦点

	BarCode [128]byte //条形码

	CameraGamma    float32
	CameraExposure float32
	CameraGain     float32

	Reversed [433]byte //保留字节
}

// 123 OK
type SqMacrographInfo struct {
	Flag uint16 /*低8位为字母’M’，高8位为字母’I '0x494D*/

	Rgb    int64
	Width  uint32
	Height uint32
	Chance uint32
	Step   uint32

	RgbSize         int64 //8
	EncodeSize      int64 //jpeg大小 8
	Quality         byte  //1
	NextLayerOffset int64 //8

	HeadSpace1 uint32   //4
	HeadSpace2 uint32   //4
	HeadSpace  [64]byte //64
}

// 2+4*5+8*2+4+8+72=122 OK
type SqPicInfo struct {
	Flag     uint16 /* 低8位为字母’F’，高8位为字母’I ' 0x4649*/
	InfoSize uint32 /* 当前结构体的大小*/
	/* 第几层 */
	Layer uint32
	/* 切片数量 */
	SliceNum uint32
	/* 切片横竖坐标的数量 */
	SliceNumX uint32
	SliceNumY uint32
	/* 图层大小 */
	LayerSize int64
	/* 下一图层结构体偏移量 */
	NextLayerOffset int64
	/* 缩放比例 */
	CurScale float32
	Ruler    float64 //微米每像素

	DefaultX  uint32 /*补白边，X或Y最后一张的X或Y存在多少像素的白边，为了固定切片的大小*/
	DefaultY  uint32
	Format    byte
	HeadSpace [63]byte //预留空间
}
