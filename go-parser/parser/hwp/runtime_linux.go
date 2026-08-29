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
typedef int32_t (*read_named_fn)(void *, hwp_image_info *);
typedef int32_t (*read_config_fn)(void *, hwp_config *);
typedef int32_t (*read_img_fn)(void *, uint32_t, uint32_t, float, hwp_image_info *);
typedef void (*destroy_image_fn)(hwp_image_info *);

typedef struct { void *lib; get_reader_fn get; destroy_reader_fn destroy; read_named_fn preview, label, thumb; read_config_fn config; read_img_fn img; destroy_image_fn destroy_image; } hwp_api;
static hwp_api api;
static int api_loaded;

static int hwp_load(const char *path) {
  if (api_loaded) return 0;
  memset(&api, 0, sizeof(api));
  api.lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!api.lib) return -1;
  api.get = (get_reader_fn)dlsym(api.lib, "GetHwpReader");
  api.destroy = (destroy_reader_fn)dlsym(api.lib, "DestroyHwpReader");
  api.preview = (read_named_fn)dlsym(api.lib, "HwpReadPreview");
  api.label = (read_named_fn)dlsym(api.lib, "HwpReadLabel");
  api.thumb = (read_named_fn)dlsym(api.lib, "HwpReadThumb");
  api.config = (read_config_fn)dlsym(api.lib, "HwpReadConfig");
  api.img = (read_img_fn)dlsym(api.lib, "HwpReadImg");
  api.destroy_image = (destroy_image_fn)dlsym(api.lib, "HwpDestroyImage");
  if (!api.get || !api.destroy || !api.preview || !api.label || !api.thumb || !api.config || !api.img || !api.destroy_image) {
    dlclose(api.lib); memset(&api, 0, sizeof(api)); return -2;
  }
  api_loaded = 1;
  return 0;
}
static const char *hwp_error(void) { const char *e = dlerror(); return e ? e : "unknown dlopen/dlsym error"; }
static uintptr_t hwp_open_reader(const char *sdk, const char *file) { if (hwp_load(sdk) != 0) return 0; return (uintptr_t)api.get(file); }
static void hwp_close_reader(uintptr_t p) { if (p) api.destroy((void *)p); }
static int32_t hwp_config_read(uintptr_t p, hwp_config *out) { return api.config((void *)p, out); }
static int32_t hwp_read_image(uintptr_t p, uint32_t x, uint32_t y, float scale, hwp_image_info *out) { memset(out, 0, sizeof(*out)); return api.img((void *)p, x, y, scale, out); }
static int32_t hwp_read_named(uintptr_t p, int kind, hwp_image_info *out) { memset(out, 0, sizeof(*out)); read_named_fn fn = kind == 0 ? api.preview : (kind == 1 ? api.label : api.thumb); return fn((void *)p, out); }
static void hwp_image_free(hwp_image_info *img) { if (img && img->data) api.destroy_image(img); }
*/
import "C"

import (
	"errors"
	"fmt"
	"os"
	"sync"
	"unsafe"
)

const maxSDKImageBytes = 64 << 20

var hwpSDKMu sync.Mutex

type platformRuntime struct{}

func hwpSDKPath() string {
	if path := os.Getenv("HWP_SDK_PATH"); path != "" {
		return path
	}
	for _, path := range []string{"/opt/vendor-libs/hwp/libhwp_sdk.so", "vendor-libs-local/hwp/libhwp_sdk.so", "../vendor-libs-local/hwp/libhwp_sdk.so"} {
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}
	return "/opt/vendor-libs/hwp/libhwp_sdk.so"
}

func (platformRuntime) open(file string) (uintptr, error) {
	hwpSDKMu.Lock()
	defer hwpSDKMu.Unlock()
	sdk := C.CString(hwpSDKPath())
	defer C.free(unsafe.Pointer(sdk))
	name := C.CString(file)
	defer C.free(unsafe.Pointer(name))
	reader := C.hwp_open_reader(sdk, name)
	if reader == 0 {
		return 0, fmt.Errorf("load %s or open slide failed: %s", hwpSDKPath(), C.GoString(C.hwp_error()))
	}
	return uintptr(reader), nil
}

func (platformRuntime) close(reader uintptr) {
	hwpSDKMu.Lock()
	defer hwpSDKMu.Unlock()
	C.hwp_close_reader(C.uintptr_t(reader))
}

func (platformRuntime) config(reader uintptr) (config, error) {
	hwpSDKMu.Lock()
	defer hwpSDKMu.Unlock()
	var value C.hwp_config
	if rc := C.hwp_config_read(C.uintptr_t(reader), &value); rc != 0 {
		return config{}, fmt.Errorf("HwpReadConfig returned %d", int32(rc))
	}
	return config{tileWidth: uint32(value.tileWidth), tileHeight: uint32(value.tileHeight), imageWidth: uint32(value.imageWidth), imageHeight: uint32(value.imageHeight), scanRatio: float32(value.scanRatio), downsample: float32(value.downsamplingRatio), mpp: float32(value.mpp)}, nil
}

func hwpImageBytes(image *C.hwp_image_info) ([]byte, uint32, uint32, error) {
	defer C.hwp_image_free(image)
	length := uint64(image.dataLen)
	if image.data == nil || length == 0 {
		return nil, 0, 0, errors.New("HWP SDK returned an empty image")
	}
	if length > maxSDKImageBytes {
		return nil, 0, 0, fmt.Errorf("HWP SDK image exceeds %d-byte safety limit", maxSDKImageBytes)
	}
	return C.GoBytes(image.data, C.int(length)), uint32(image.width), uint32(image.height), nil
}

func (platformRuntime) readImage(reader uintptr, x, y uint32, scale float32) ([]byte, uint32, uint32, error) {
	hwpSDKMu.Lock()
	defer hwpSDKMu.Unlock()
	var image C.hwp_image_info
	if rc := C.hwp_read_image(C.uintptr_t(reader), C.uint32_t(x), C.uint32_t(y), C.float(scale), &image); rc != 0 {
		return nil, 0, 0, fmt.Errorf("HwpReadImg returned %d", int32(rc))
	}
	return hwpImageBytes(&image)
}

func (platformRuntime) readNamed(reader uintptr, kind int) ([]byte, uint32, uint32, error) {
	hwpSDKMu.Lock()
	defer hwpSDKMu.Unlock()
	var image C.hwp_image_info
	if rc := C.hwp_read_named(C.uintptr_t(reader), C.int(kind), &image); rc != 0 {
		return nil, 0, 0, fmt.Errorf("HWP named image read returned %d", int32(rc))
	}
	return hwpImageBytes(&image)
}
