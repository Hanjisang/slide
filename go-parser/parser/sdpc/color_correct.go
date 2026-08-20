package sdpc

import (
	"errors"
	"image"
	"image/draw"
	"math"
)

const maxColorDimension = 4096

type ColorCorrector struct {
	rgbRate    [3]float32
	hsvRate    [3]float32
	ccm        [3][3]float32
	gammaTable [256]byte
}

func newColorCorrector(rgbRate, hsvRate [3]float32, gamma float32, ccm [3][3]float32) (*ColorCorrector, error) {
	if !finite(gamma) || gamma <= 0 {
		return nil, errors.New("invalid SDPC gamma value")
	}
	for _, value := range rgbRate {
		if !finite(value) {
			return nil, errors.New("invalid SDPC RGB correction value")
		}
	}
	for _, value := range hsvRate {
		if !finite(value) {
			return nil, errors.New("invalid SDPC HSV correction value")
		}
	}
	for _, row := range ccm {
		for _, value := range row {
			if !finite(value) {
				return nil, errors.New("invalid SDPC color matrix value")
			}
		}
	}
	return &ColorCorrector{
		rgbRate:    rgbRate,
		hsvRate:    hsvRate,
		ccm:        ccm,
		gammaTable: getGammaTable(gamma),
	}, nil
}

func finite(value float32) bool {
	v := float64(value)
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}

func (corrector *ColorCorrector) Apply(source image.Image) (*image.RGBA, error) {
	if corrector == nil || source == nil {
		return nil, errors.New("SDPC color correction is unavailable")
	}
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width <= 0 || height <= 0 || width > maxColorDimension || height > maxColorDimension {
		return nil, errors.New("invalid SDPC image dimensions")
	}

	result := image.NewRGBA(image.Rect(0, 0, width, height))
	draw.Draw(result, result.Bounds(), source, bounds.Min, draw.Src)
	for y := 0; y < height; y++ {
		row := y * result.Stride
		for x := 0; x < width; x++ {
			pos := row + x*4
			r, g, b := corrector.correct(result.Pix[pos], result.Pix[pos+1], result.Pix[pos+2])
			result.Pix[pos] = r
			result.Pix[pos+1] = g
			result.Pix[pos+2] = b
			result.Pix[pos+3] = 255
		}
	}
	return result, nil
}

func (corrector *ColorCorrector) correct(r, g, b byte) (byte, byte, byte) {
	red := floatInByteRange(float64((corrector.ccm[0][0]*float32(r) + corrector.ccm[0][1]*float32(g) + corrector.ccm[0][2]*float32(b)) * corrector.rgbRate[0]))
	green := floatInByteRange(float64((corrector.ccm[1][0]*float32(r) + corrector.ccm[1][1]*float32(g) + corrector.ccm[1][2]*float32(b)) * corrector.rgbRate[1]))
	blue := floatInByteRange(float64((corrector.ccm[2][0]*float32(r) + corrector.ccm[2][1]*float32(g) + corrector.ccm[2][2]*float32(b)) * corrector.rgbRate[2]))

	hsv := rgb2Hsv([3]byte{corrector.gammaTable[red], corrector.gammaTable[green], corrector.gammaTable[blue]}, corrector.hsvRate)
	rgb := hsv2Rgb(hsv, [3]float32{1, 1, 1})
	return rgb[0], rgb[1], rgb[2]
}

func hsv2Rgb(hsv [3]float32, rgbRate [3]float32) (rgb [3]byte) {
	h := hsv[0]
	s := hsv[1]
	v := hsv[2]

	if math.Abs(float64(s)) <= 0.000001 {
		V := scalarRgb(255, v)
		rgb[0] = scalarRgb(V, rgbRate[0])
		rgb[1] = scalarRgb(V, rgbRate[1])
		rgb[2] = scalarRgb(V, rgbRate[2])
		return
	}
	h /= 60
	i := float32(math.Floor(float64(h)))
	f := h - i
	a := v * (1 - s)
	b := v * (1 - s*f)
	c := v * (1 - s*(1-f))
	var R, G, B byte
	switch i {
	case 0:
		R = scalarRgb(255, v)
		G = scalarRgb(255, c)
		B = scalarRgb(255, a)
	case 1:
		R = scalarRgb(255, b)
		G = scalarRgb(255, v)
		B = scalarRgb(255, a)
	case 2:
		R = scalarRgb(255, a)
		G = scalarRgb(255, v)
		B = scalarRgb(255, c)
	case 3:
		R = scalarRgb(255, a)
		G = scalarRgb(255, b)
		B = scalarRgb(255, v)
	case 4:
		R = scalarRgb(255, c)
		G = scalarRgb(255, a)
		B = scalarRgb(255, v)
	case 5:
		R = scalarRgb(255, v)
		G = scalarRgb(255, a)
		B = scalarRgb(255, b)
	}
	rgb[0] = scalarRgb(R, rgbRate[0])
	rgb[1] = scalarRgb(G, rgbRate[1])
	rgb[2] = scalarRgb(B, rgbRate[2])
	return
}

func scalarRgb(rgb byte, rgbRate float32) byte {
	return floatInByteRange(float64(float32(rgb) * rgbRate))
}

func rgb2Hsv(rgb [3]byte, hsvRate [3]float32) (hsv [3]float32) {
	r := float64(rgb[0]) / 255
	g := float64(rgb[1]) / 255
	b := float64(rgb[2]) / 255

	cMax := math.Max(math.Max(r, g), b)
	cMin := math.Min(math.Min(r, g), b)
	diff := cMax - cMin

	if diff <= 0.000001 {
		hsv[0] = 0
	} else if math.Abs(r-cMax) <= 0.000001 {
		if g-b > 0.000001 {
			hsv[0] = float32(60 * ((g - b) / diff))
		} else if b-g > 0.000001 {
			hsv[0] = float32(60*((g-b)/diff) + 359)
		}
	} else if math.Abs(g-cMax) <= 0.000001 {
		hsv[0] = float32(60*((b-r)/diff) + 119)
	} else if math.Abs(b-cMax) <= 0.000001 {
		hsv[0] = float32(60*((r-g)/diff) + 239)
	}

	if cMax > 0.000001 {
		hsv[1] = float32(diff / cMax)
	}
	hsv[2] = float32(cMax)
	hsv[0] *= hsvRate[0]
	hsv[1] *= hsvRate[1]
	hsv[2] *= hsvRate[2]

	if hsv[0]-359 > 0.000001 {
		hsv[0] = 359
	} else if hsv[0] <= 0.000001 {
		hsv[0] = 0
	}
	if hsv[1]-1 > 0.000001 {
		hsv[1] = 1
	} else if hsv[1] <= 0.000001 {
		hsv[1] = 0
	}
	if hsv[2]-1 > 0.000001 {
		hsv[2] = 1
	} else if hsv[2] <= 0.000001 {
		hsv[2] = 0
	}
	return
}

func floatInByteRange(f float64) byte {
	r := int(f)
	if r < 0 {
		return 0
	}
	if r > 255 {
		return 255
	}
	return byte(r)
}

func getGammaTable(gamma float32) [256]byte {
	fPrecompensation := float64(1 / gamma)
	var gammaTable [256]byte
	for i := 0; i < 256; i++ {
		f := float64((float32(i) + 0.5) / 256)
		f = math.Pow(f, fPrecompensation)
		gammaTable[i] = floatInByteRange(math.Pow(f, fPrecompensation)*256 - 0.5)
	}
	return gammaTable
}
