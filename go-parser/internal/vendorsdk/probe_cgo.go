//go:build linux && cgo

package vendorsdk

/*
#cgo LDFLAGS: -ldl
#include <dlfcn.h>
#include <stdlib.h>

static void* sdk_open(const char* path) { return dlopen(path, RTLD_NOW | RTLD_LOCAL); }
static void* sdk_symbol(void* handle, const char* name) { return dlsym(handle, name); }
static const char* sdk_error() { return dlerror(); }
static void sdk_close(void* handle) { if (handle != NULL) dlclose(handle); }
*/
import "C"

import (
	"fmt"
	"unsafe"
)

func Probe(path string, symbols []string) error {
	cpath := C.CString(path)
	defer C.free(unsafe.Pointer(cpath))
	handle := C.sdk_open(cpath)
	if handle == nil {
		return fmt.Errorf("dlopen failed: %s", C.GoString(C.sdk_error()))
	}
	defer C.sdk_close(handle)
	for _, symbol := range symbols {
		csymbol := C.CString(symbol)
		found := C.sdk_symbol(handle, csymbol)
		C.free(unsafe.Pointer(csymbol))
		if found == nil {
			return fmt.Errorf("required symbol %s not found", symbol)
		}
	}
	return nil
}

func Enabled() bool { return true }
