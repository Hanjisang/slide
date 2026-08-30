//go:build linux && cgo

package tron

/*
#cgo LDFLAGS: -ldl
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint32_t first, second; } tron_pair_u32;
typedef struct { uint32_t x, y, width, height; } tron_region;
typedef struct { float first, second; } tron_pair_f32;
typedef struct { uint8_t valid; uint8_t padding[7]; uint64_t width, height, length; } tron_image_info;

typedef void *(*tron_open_fn)(const char *);
typedef void (*tron_close_fn)(void *);
typedef int32_t (*tron_error_fn)(void);
typedef tron_pair_u32 (*tron_pair_fn)(void *);
typedef tron_region (*tron_region_fn)(void *);
typedef tron_pair_f32 (*tron_float_pair_fn)(void *);
typedef uint32_t (*tron_u32_fn)(void *);
typedef tron_image_info (*tron_image_info_fn)(void *, uint32_t, uint32_t, uint32_t, uint32_t);
typedef tron_image_info (*tron_named_info_fn)(void *, const char *);
typedef size_t (*tron_tile_data_fn)(void *, uint32_t, uint32_t, uint32_t, uint32_t, void *);
typedef size_t (*tron_named_data_fn)(void *, const char *, void *);

typedef struct {
  void *lib;
  tron_open_fn open;
  tron_close_fn close;
  tron_error_fn last_error;
  tron_pair_fn tile_size;
  tron_region_fn content_region;
  tron_pair_fn lod_range;
  tron_float_pair_fn resolution;
  tron_u32_fn representative_layer;
  tron_image_info_fn tile_info;
  tron_named_info_fn named_info;
  tron_tile_data_fn tile_data;
  tron_named_data_fn named_data;
} tron_api;
static tron_api api;
static int api_loaded;

static int tron_load(const char *path) {
  if (api_loaded) return 0;
  memset(&api, 0, sizeof(api));
  api.lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!api.lib) return -1;
  api.open = (tron_open_fn)dlsym(api.lib, "tron_open");
  api.close = (tron_close_fn)dlsym(api.lib, "tron_close");
  api.last_error = (tron_error_fn)dlsym(api.lib, "tron_get_last_error");
  api.tile_size = (tron_pair_fn)dlsym(api.lib, "tron_get_tile_size");
  api.content_region = (tron_region_fn)dlsym(api.lib, "tron_get_content_region");
  api.lod_range = (tron_pair_fn)dlsym(api.lib, "tron_get_lod_level_range");
  api.resolution = (tron_float_pair_fn)dlsym(api.lib, "tron_get_resolution");
  api.representative_layer = (tron_u32_fn)dlsym(api.lib, "tron_get_representative_layer_index");
  api.tile_info = (tron_image_info_fn)dlsym(api.lib, "tron_get_tile_image_info");
  api.named_info = (tron_named_info_fn)dlsym(api.lib, "tron_get_named_image_info");
  api.tile_data = (tron_tile_data_fn)dlsym(api.lib, "tron_get_tile_image_data");
  api.named_data = (tron_named_data_fn)dlsym(api.lib, "tron_get_named_image_data");
  if (!api.open || !api.close || !api.last_error || !api.tile_size || !api.content_region || !api.lod_range || !api.resolution || !api.representative_layer || !api.tile_info || !api.named_info || !api.tile_data || !api.named_data) {
    dlclose(api.lib); memset(&api, 0, sizeof(api)); return -2;
  }
  api_loaded = 1;
  return 0;
}
static const char *tron_dl_error(void) { const char *e = dlerror(); return e ? e : "unknown dlopen/dlsym error"; }
static uintptr_t tron_open_reader(const char *sdk, const char *file) { if (tron_load(sdk) != 0) return 0; return (uintptr_t)api.open(file); }
static void tron_close_reader(uintptr_t p) { if (p) api.close((void *)p); }
static int32_t tron_last_error(void) { return api.last_error ? api.last_error() : -1; }
static tron_pair_u32 tron_tile_size(uintptr_t p) { return api.tile_size((void *)p); }
static tron_region tron_content_region(uintptr_t p) { return api.content_region((void *)p); }
static tron_pair_u32 tron_lod_range(uintptr_t p) { return api.lod_range((void *)p); }
static tron_pair_f32 tron_resolution(uintptr_t p) { return api.resolution((void *)p); }
static uint32_t tron_representative_layer(uintptr_t p) { return api.representative_layer((void *)p); }
static tron_image_info tron_tile_info(uintptr_t p, uint32_t lod, uint32_t layer, uint32_t col, uint32_t row) { return api.tile_info((void *)p, lod, layer, col, row); }
static tron_image_info tron_named_info(uintptr_t p, const char *name) { return api.named_info((void *)p, name); }
static size_t tron_tile_data(uintptr_t p, uint32_t lod, uint32_t layer, uint32_t col, uint32_t row, void *out) { return api.tile_data((void *)p, lod, layer, col, row, out); }
static size_t tron_named_data(uintptr_t p, const char *name, void *out) { return api.named_data((void *)p, name, out); }
*/
import "C"

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"os"
	"sync"
	"unsafe"
)

var tronSDKMu sync.Mutex

type platformRuntime struct{}

func tronSDKPath() string {
	if path := os.Getenv("TRON_SDK_PATH"); path != "" {
		return path
	}
	for _, path := range []string{"/opt/vendor-libs/tron/libtronc.so", "vendor-libs-local/tron/libtronc.so", "../vendor-libs-local/tron/libtronc.so"} {
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}
	return "/opt/vendor-libs/tron/libtronc.so"
}

func (platformRuntime) open(file string) (uintptr, error) {
	tronSDKMu.Lock()
	defer tronSDKMu.Unlock()
	sdk := C.CString(tronSDKPath())
	defer C.free(unsafe.Pointer(sdk))
	name := C.CString(file)
	defer C.free(unsafe.Pointer(name))
	reader := C.tron_open_reader(sdk, name)
	if reader == 0 {
		return 0, fmt.Errorf("load %s or open slide failed: %s (TRON error %d)", tronSDKPath(), C.GoString(C.tron_dl_error()), int32(C.tron_last_error()))
	}
	return uintptr(reader), nil
}

func (platformRuntime) close(reader uintptr) {
	tronSDKMu.Lock()
	defer tronSDKMu.Unlock()
	C.tron_close_reader(C.uintptr_t(reader))
}

func (platformRuntime) metadata(reader uintptr) (metadata, error) {
	tronSDKMu.Lock()
	defer tronSDKMu.Unlock()
	tileSize := C.tron_tile_size(C.uintptr_t(reader))
	region := C.tron_content_region(C.uintptr_t(reader))
	lod := C.tron_lod_range(C.uintptr_t(reader))
	resolution := C.tron_resolution(C.uintptr_t(reader))
	return metadata{width: uint32(region.width), height: uint32(region.height), tileWidth: uint32(tileSize.first), tileHeight: uint32(tileSize.second), lodMin: uint32(lod.first), lodMax: uint32(lod.second), layerIndex: uint32(C.tron_representative_layer(C.uintptr_t(reader))), mppX: float32(resolution.first), mppY: float32(resolution.second)}, nil
}

func copyTRONData(length uint64, fill func(unsafe.Pointer) uint64) ([]byte, error) {
	if length == 0 || length > 64<<20 {
		return nil, fmt.Errorf("TRON image length %d exceeds safety limits", length)
	}
	buffer := C.malloc(C.size_t(length))
	if buffer == nil {
		return nil, errors.New("TRON image allocation failed")
	}
	defer C.free(buffer)
	written := fill(buffer)
	if written == 0 || written > length {
		return nil, fmt.Errorf("TRON SDK returned invalid image length %d (capacity %d)", written, length)
	}
	return C.GoBytes(buffer, C.int(written)), nil
}

func encodeTRONImage(data []byte, width, height uint64) ([]byte, error) {
	if len(data) >= 3 && data[0] == 0xff && data[1] == 0xd8 && data[2] == 0xff {
		return data, nil
	}
	if width == 0 || height == 0 || width > 16384 || height > 16384 || width > uint64(^uint(0)>>1)/height {
		return nil, fmt.Errorf("invalid TRON image dimensions %dx%d", width, height)
	}
	pixels := width * height
	channels := uint64(0)
	if uint64(len(data)) == pixels*3 {
		channels = 3
	} else if uint64(len(data)) == pixels*4 {
		channels = 4
	} else {
		prefix := data
		if len(prefix) > 12 {
			prefix = prefix[:12]
		}
		return nil, fmt.Errorf("TRON SDK returned unsupported image payload: %dx%d bytes=%d prefix=%x", width, height, len(data), prefix)
	}
	img := image.NewRGBA(image.Rect(0, 0, int(width), int(height)))
	for src, dst := 0, 0; src < len(data); src, dst = src+int(channels), dst+4 {
		img.Pix[dst] = data[src]
		img.Pix[dst+1] = data[src+1]
		img.Pix[dst+2] = data[src+2]
		img.Pix[dst+3] = 0xff
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, img, &jpeg.Options{Quality: 92}); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

func (platformRuntime) readTile(reader uintptr, lod, layer, col, row uint32) ([]byte, error) {
	tronSDKMu.Lock()
	defer tronSDKMu.Unlock()
	info := C.tron_tile_info(C.uintptr_t(reader), C.uint32_t(lod), C.uint32_t(layer), C.uint32_t(col), C.uint32_t(row))
	if info.valid == 0 {
		return nil, fmt.Errorf("tron_get_tile_image_info failed for %d/%d/%d/%d (TRON error %d)", lod, layer, col, row, int32(C.tron_last_error()))
	}
	data, err := copyTRONData(uint64(info.length), func(out unsafe.Pointer) uint64 {
		return uint64(C.tron_tile_data(C.uintptr_t(reader), C.uint32_t(lod), C.uint32_t(layer), C.uint32_t(col), C.uint32_t(row), out))
	})
	if err != nil {
		return nil, err
	}
	return encodeTRONImage(data, uint64(info.width), uint64(info.height))
}

func (platformRuntime) readNamed(reader uintptr, name string) ([]byte, error) {
	tronSDKMu.Lock()
	defer tronSDKMu.Unlock()
	cname := C.CString(name)
	defer C.free(unsafe.Pointer(cname))
	info := C.tron_named_info(C.uintptr_t(reader), cname)
	if info.valid == 0 {
		return nil, fmt.Errorf("TRON named image %q is not available (TRON error %d)", name, int32(C.tron_last_error()))
	}
	data, err := copyTRONData(uint64(info.length), func(out unsafe.Pointer) uint64 { return uint64(C.tron_named_data(C.uintptr_t(reader), cname, out)) })
	if err != nil {
		return nil, err
	}
	return encodeTRONImage(data, uint64(info.width), uint64(info.height))
}
