//go:build linux && cgo

package hwp

/*
#cgo LDFLAGS: -ldl
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint32_t x, y, width, height; void *data; uint32_t dataLen; } hwp_image_info;
typedef struct { uint32_t tileWidth, tileHeight, imageWidth, imageHeight; float scanRatio; uint8_t downsamplingMode; float downsamplingRatio; float mpp; } hwp_config;
typedef void *(*get_reader_fn)(const char *);
typedef void (*destroy_reader_fn)(void *);
typedef int (*read_preview_fn)(void *, hwp_image_info *);
typedef int (*read_config_fn)(void *, hwp_config *);
typedef int (*read_img_fn)(void *, uint32_t, uint32_t, float, hwp_image_info *);
typedef void (*destroy_image_fn)(hwp_image_info *);

typedef struct { void *lib; get_reader_fn get; destroy_reader_fn destroy; read_preview_fn preview, label, thumb; read_config_fn config; read_img_fn img; destroy_image_fn destroy_image; } hwp_api;
static hwp_api api;
static int api_loaded;

static int hwp_load(const char *path) {
  if (api_loaded) return 0;
  api.lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!api.lib) return -1;
  api.get = (get_reader_fn)dlsym(api.lib, "GetHwpReader");
  api.destroy = (destroy_reader_fn)dlsym(api.lib, "DestroyHwpReader");
  api.preview = (read_preview_fn)dlsym(api.lib, "HwpReadPreview");
  api.label = (read_preview_fn)dlsym(api.lib, "HwpReadLabel");
  api.thumb = (read_preview_fn)dlsym(api.lib, "HwpReadThumb");
  api.config = (read_config_fn)dlsym(api.lib, "HwpReadConfig");
  api.img = (read_img_fn)dlsym(api.lib, "HwpReadImg");
  api.destroy_image = (destroy_image_fn)dlsym(api.lib, "HwpDestroyImage");
  if (!api.get || !api.destroy || !api.preview || !api.label || !api.thumb || !api.config || !api.img || !api.destroy_image) return -2;
  api_loaded = 1; return 0;
}
static const char *hwp_error(void) { return dlerror(); }
static uintptr_t hwp_open_reader(const char *sdk, const char *file) { if (hwp_load(sdk) != 0) return 0; return (uintptr_t)api.get(file); }
static void hwp_close_reader(uintptr_t p) { if (p) api.destroy((void *)p); }
static int hwp_config_read(uintptr_t p, hwp_config *out) { return api.config((void *)p, out); }
static int hwp_read_image(uintptr_t p, uint32_t x, uint32_t y, float scale, hwp_image_info *out) { memset(out, 0, sizeof(*out)); return api.img((void *)p, x, y, scale, out); }
static int hwp_read_named(uintptr_t p, int kind, hwp_image_info *out) { memset(out, 0, sizeof(*out)); read_preview_fn fn = kind == 0 ? api.preview : (kind == 1 ? api.label : api.thumb); return fn((void *)p, out); }
static void hwp_image_free(hwp_image_info *img) { if (img && img->data) api.destroy_image(img); }
*/
import "C"

import (
	"errors"
	"fmt"
	"os"
	"unsafe"
)

func sdkPath() string {
	if p := os.Getenv("HWP_SDK_PATH"); p != "" {
		return p
	}
	for _, p := range []string{"/opt/vendor/hwp/libhwp_sdk.so", "/opt/vendor/hwp/hwp/linux/libhwp_sdk.so", "vendor-libs-local/hwp/libhwp_sdk.so", "vendor-libs-local/hwp/hwp/linux/libhwp_sdk.so", "../vendor-libs-local/hwp/libhwp_sdk.so", "../vendor-libs-local/hwp/hwp/linux/libhwp_sdk.so"} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return "/opt/vendor/hwp/libhwp_sdk.so"
}

func runtimeOpen(file string) (uintptr, error) {
	sdk := C.CString(sdkPath())
	defer C.free(unsafe.Pointer(sdk))
	name := C.CString(file)
	defer C.free(unsafe.Pointer(name))
	p := C.hwp_open_reader(sdk, name)
	if p == 0 {
		return 0, fmt.Errorf("HWP SDK open failed: %s", C.GoString(C.hwp_error()))
	}
	return uintptr(p), nil
}
func runtimeClose(p uintptr) { C.hwp_close_reader(C.uintptr_t(p)) }
func runtimeConfig(p uintptr) (config, error) {
	var c C.hwp_config
	if rc := C.hwp_config_read(C.uintptr_t(p), &c); rc != 0 {
		return config{}, fmt.Errorf("HwpReadConfig returned %d", int(rc))
	}
	return config{uint32(c.tileWidth), uint32(c.tileHeight), uint32(c.imageWidth), uint32(c.imageHeight), float32(c.scanRatio), float32(c.downsamplingRatio), float32(c.mpp)}, nil
}
func readResult(img *C.hwp_image_info) ([]byte, uint32, uint32, error) {
	if img.data == nil || img.dataLen == 0 {
		return nil, 0, 0, errors.New("HWP SDK returned empty image")
	}
	data := C.GoBytes(img.data, C.int(img.dataLen))
	w, h := uint32(img.width), uint32(img.height)
	C.hwp_image_free(img)
	return data, w, h, nil
}
func runtimeReadImage(p uintptr, x, y uint32, scale float32) ([]byte, uint32, uint32, error) {
	var img C.hwp_image_info
	rc := C.hwp_read_image(C.uintptr_t(p), C.uint32_t(x), C.uint32_t(y), C.float(scale), &img)
	if rc != 0 {
		return nil, 0, 0, fmt.Errorf("HwpReadImg returned %d", int(rc))
	}
	return readResult(&img)
}
func runtimeReadNamed(p uintptr, kind int) ([]byte, uint32, uint32, error) {
	var img C.hwp_image_info
	rc := C.hwp_read_named(C.uintptr_t(p), C.int(kind), &img)
	if rc != 0 {
		return nil, 0, 0, fmt.Errorf("HWP image read returned %d", int(rc))
	}
	return readResult(&img)
}
