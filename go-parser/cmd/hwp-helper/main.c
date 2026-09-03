#include <dlfcn.h>
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct {
  uint32_t x, y, width, height;
  void *data;
  uint32_t dataLen;
} hwp_image_info;

typedef struct {
  uint32_t tileWidth, tileHeight, imageWidth, imageHeight;
  float scanRatio;
  uint8_t downsamplingMode;
  float downsamplingRatio;
  float mpp;
} hwp_config;

typedef struct { void *begin, *end, *capacity; } vector_view;
typedef struct { uint32_t startX, endX, startY, endY; } hwp_rect;
typedef struct {
  hwp_rect rect;
  uint64_t dataOffset, dataLength;
  void *buffer;
  uint32_t crc32, padding;
} tile_info;
typedef struct {
  uint32_t id, width, height;
  float ratio;
  uint64_t size;
  uint8_t rois[24], newRois[24];
  vector_view tiles;
} frame_info;
typedef struct {
  uint32_t id, channels, planarConfiguration, dataRepresentation;
  uint32_t z;
  float compressionRatio;
  uint8_t sampleBlocks[24];
  vector_view frames;
  uint8_t rois[24], asyncRois[24];
} image_info;
typedef struct { uint8_t config[120]; vector_view images; } scan_result;
typedef struct {
  float ratio;
  uint32_t width, height, originX, originY, endX, endY;
} frame_record;

_Static_assert(sizeof(vector_view) == 24, "unexpected vector ABI");
_Static_assert(sizeof(tile_info) == 48, "unexpected TileInfo ABI");
_Static_assert(sizeof(frame_info) == 96, "unexpected FrameInfo ABI");
_Static_assert(sizeof(image_info) == 120, "unexpected ImageInfo ABI");
_Static_assert(sizeof(scan_result) == 144, "unexpected ScanResult ABI");

typedef void *(*get_reader_fn)(const char *);
typedef void (*destroy_reader_fn)(void *);
typedef int32_t (*read_named_fn)(void *, hwp_image_info *);
typedef int32_t (*read_config_fn)(void *, hwp_config *);
typedef int32_t (*read_img_fn)(void *, uint32_t, uint32_t, float, hwp_image_info *);
typedef void (*destroy_image_fn)(hwp_image_info *);

typedef struct {
  void *lib;
  get_reader_fn get;
  destroy_reader_fn destroy;
  read_named_fn preview, label, thumb;
  read_config_fn config;
  read_img_fn img;
  destroy_image_fn destroy_image;
} hwp_api;

static int protocol_fd = -1;

static void put_u32(uint8_t *out, uint32_t value) {
  out[0] = (uint8_t)value;
  out[1] = (uint8_t)(value >> 8);
  out[2] = (uint8_t)(value >> 16);
  out[3] = (uint8_t)(value >> 24);
}

static void put_u64(uint8_t *out, uint64_t value) {
  put_u32(out, (uint32_t)value);
  put_u32(out + 4, (uint32_t)(value >> 32));
}

static uint32_t get_u32(const uint8_t *in) {
  return (uint32_t)in[0] | ((uint32_t)in[1] << 8) |
         ((uint32_t)in[2] << 16) | ((uint32_t)in[3] << 24);
}

static int write_all(int fd, const void *buffer, size_t length) {
  const uint8_t *cursor = (const uint8_t *)buffer;
  while (length > 0) {
    ssize_t written = write(fd, cursor, length);
    if (written < 0 && errno == EINTR) continue;
    if (written <= 0) return -1;
    cursor += (size_t)written;
    length -= (size_t)written;
  }
  return 0;
}

static int read_all(int fd, void *buffer, size_t length) {
  uint8_t *cursor = (uint8_t *)buffer;
  while (length > 0) {
    ssize_t received = read(fd, cursor, length);
    if (received < 0 && errno == EINTR) continue;
    if (received <= 0) return -1;
    cursor += (size_t)received;
    length -= (size_t)received;
  }
  return 0;
}

static int vector_count(const vector_view *vector, size_t element_size,
                        size_t maximum, size_t *count) {
  uintptr_t begin = (uintptr_t)vector->begin;
  uintptr_t end = (uintptr_t)vector->end;
  uintptr_t capacity = (uintptr_t)vector->capacity;
  if (begin == 0 && end == 0 && capacity == 0) {
    *count = 0;
    return 0;
  }
  if (begin == 0 || begin > end || end > capacity ||
      (end - begin) % element_size != 0 ||
      (capacity - begin) % element_size != 0) return -1;
  *count = (end - begin) / element_size;
  return *count <= maximum ? 0 : -1;
}

static int collect_frames(void *reader, frame_record *records,
                          uint32_t *record_count, char *error,
                          size_t error_size) {
  vector_view *scans = (vector_view *)((uint8_t *)reader + 816);
  size_t scan_count = 0, image_count = 0, frame_count = 0;
  if (vector_count(scans, sizeof(scan_result), 64, &scan_count) != 0 || scan_count == 0) {
    snprintf(error, error_size, "invalid HWP scan metadata");
    return -1;
  }
  scan_result *scan = (scan_result *)scans->begin;
  if (vector_count(&scan->images, sizeof(image_info), 64, &image_count) != 0 || image_count == 0) {
    snprintf(error, error_size, "invalid HWP image metadata");
    return -1;
  }
  image_info *image = (image_info *)scan->images.begin;
  if (vector_count(&image->frames, sizeof(frame_info), 32, &frame_count) != 0 || frame_count == 0) {
    snprintf(error, error_size, "invalid HWP frame metadata");
    return -1;
  }

  *record_count = 0;
  frame_info *frames = (frame_info *)image->frames.begin;
  for (size_t index = 0; index < frame_count; index++) {
    frame_info *frame = &frames[index];
    size_t tile_count = 0;
    if (!isfinite(frame->ratio) || frame->ratio <= 0 ||
        frame->width == 0 || frame->height == 0 ||
        vector_count(&frame->tiles, sizeof(tile_info), 1000000, &tile_count) != 0 ||
        tile_count == 0) continue;

    tile_info *tiles = (tile_info *)frame->tiles.begin;
    uint32_t min_x = UINT32_MAX, min_y = UINT32_MAX, max_x = 0, max_y = 0;
    int valid = 1;
    for (size_t tile_index = 0; tile_index < tile_count; tile_index++) {
      hwp_rect rect = tiles[tile_index].rect;
      if (rect.startX > rect.endX || rect.startY > rect.endY) {
        valid = 0;
        break;
      }
      if (rect.startX < min_x) min_x = rect.startX;
      if (rect.startY < min_y) min_y = rect.startY;
      if (rect.endX > max_x) max_x = rect.endX;
      if (rect.endY > max_y) max_y = rect.endY;
    }
    if (!valid) continue;
    records[*record_count] = (frame_record){
      frame->ratio, frame->width, frame->height, min_x, min_y, max_x, max_y
    };
    (*record_count)++;
  }
  if (*record_count == 0) {
    snprintf(error, error_size, "HWP contains no readable tiled frames");
    return -1;
  }
  return 0;
}

static int send_config(int32_t status, const hwp_config *config,
                       const frame_record *frames, uint32_t frame_count,
                       const char *error) {
  uint8_t header[44] = {0};
  uint32_t error_length = error ? (uint32_t)strlen(error) : 0;
  memcpy(header, "HWPC", 4);
  put_u32(header + 4, (uint32_t)status);
  if (config) {
    put_u32(header + 8, config->tileWidth);
    put_u32(header + 12, config->tileHeight);
    put_u32(header + 16, config->imageWidth);
    put_u32(header + 20, config->imageHeight);
    memcpy(header + 24, &config->scanRatio, sizeof(float));
    memcpy(header + 28, &config->downsamplingRatio, sizeof(float));
    memcpy(header + 32, &config->mpp, sizeof(float));
  }
  put_u32(header + 36, frame_count);
  put_u32(header + 40, error_length);
  if (write_all(protocol_fd, header, sizeof(header)) != 0) return -1;
  if (error_length != 0 && write_all(protocol_fd, error, error_length) != 0) return -1;
  for (uint32_t index = 0; index < frame_count; index++) {
    uint8_t record[28] = {0};
    memcpy(record, &frames[index].ratio, sizeof(float));
    put_u32(record + 4, frames[index].width);
    put_u32(record + 8, frames[index].height);
    put_u32(record + 12, frames[index].originX);
    put_u32(record + 16, frames[index].originY);
    put_u32(record + 20, frames[index].endX);
    put_u32(record + 24, frames[index].endY);
    if (write_all(protocol_fd, record, sizeof(record)) != 0) return -1;
  }
  return 0;
}

static int send_image(int32_t status, const hwp_image_info *image, const char *error) {
  uint8_t header[28] = {0};
  uint32_t error_length = error ? (uint32_t)strlen(error) : 0;
  memcpy(header, "HWPI", 4);
  put_u32(header + 4, (uint32_t)status);
  if (image) {
    put_u32(header + 8, image->width);
    put_u32(header + 12, image->height);
    put_u64(header + 16, image->dataLen);
  }
  put_u32(header + 24, error_length);
  if (write_all(protocol_fd, header, sizeof(header)) != 0) return -1;
  if (error_length != 0 && write_all(protocol_fd, error, error_length) != 0) return -1;
  if (status == 0 && image && image->data && image->dataLen != 0) {
    return write_all(protocol_fd, image->data, image->dataLen);
  }
  return 0;
}

static int load_api(const char *path, hwp_api *api, char *error, size_t error_size) {
  memset(api, 0, sizeof(*api));
  api->lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!api->lib) {
    snprintf(error, error_size, "dlopen %s: %s", path, dlerror());
    return -1;
  }
#define LOAD_SYMBOL(field, name) do { \
  api->field = (typeof(api->field))dlsym(api->lib, name); \
  if (!api->field) { snprintf(error, error_size, "missing HWP SDK symbol %s", name); return -1; } \
} while (0)
  LOAD_SYMBOL(get, "GetHwpReader");
  LOAD_SYMBOL(destroy, "DestroyHwpReader");
  LOAD_SYMBOL(preview, "HwpReadPreview");
  LOAD_SYMBOL(label, "HwpReadLabel");
  LOAD_SYMBOL(thumb, "HwpReadThumb");
  LOAD_SYMBOL(config, "HwpReadConfig");
  LOAD_SYMBOL(img, "HwpReadImg");
  LOAD_SYMBOL(destroy_image, "HwpDestroyImage");
#undef LOAD_SYMBOL
  return 0;
}

int main(int argc, char **argv) {
  if (argc != 3) return 64;

  protocol_fd = dup(STDOUT_FILENO);
  if (protocol_fd < 0) return 65;
  if (dup2(STDERR_FILENO, STDOUT_FILENO) < 0) return 65;

  char error[512] = {0};
  hwp_api api;
  if (load_api(argv[1], &api, error, sizeof(error)) != 0) {
    send_config(-1, NULL, NULL, 0, error);
    return 66;
  }

  void *reader = api.get(argv[2]);
  if (!reader) {
    snprintf(error, sizeof(error), "HWP SDK could not open %s", argv[2]);
    send_config(-2, NULL, NULL, 0, error);
    dlclose(api.lib);
    return 67;
  }

  hwp_config config;
  memset(&config, 0, sizeof(config));
  int32_t status = api.config(reader, &config);
  if (status != 0) {
    snprintf(error, sizeof(error), "HwpReadConfig returned %d", status);
    send_config(status, NULL, NULL, 0, error);
    api.destroy(reader);
    dlclose(api.lib);
    return 68;
  }
  frame_record frames[32];
  uint32_t frame_count = 0;
  if (collect_frames(reader, frames, &frame_count, error, sizeof(error)) != 0) {
    send_config(-3, NULL, NULL, 0, error);
    api.destroy(reader);
    dlclose(api.lib);
    return 69;
  }
  if (send_config(0, &config, frames, frame_count, NULL) != 0) {
    api.destroy(reader);
    dlclose(api.lib);
    return 70;
  }

  for (;;) {
    uint8_t request[16];
    if (read_all(STDIN_FILENO, request, sizeof(request)) != 0) break;
    uint32_t operation = get_u32(request);
    if (operation == 5) break;

    hwp_image_info image;
    memset(&image, 0, sizeof(image));
    if (operation == 1) {
      float scale;
      uint32_t scale_bits = get_u32(request + 12);
      memcpy(&scale, &scale_bits, sizeof(scale));
      status = api.img(reader, get_u32(request + 4), get_u32(request + 8), scale, &image);
    } else if (operation >= 2 && operation <= 4) {
      read_named_fn function = operation == 2 ? api.preview : (operation == 3 ? api.label : api.thumb);
      status = function(reader, &image);
    } else {
      send_image(-100, NULL, "unknown HWP helper operation");
      continue;
    }

    if (status != 0) {
      snprintf(error, sizeof(error), "HWP SDK image call returned %d", status);
      if (send_image(status, NULL, error) != 0) break;
    } else if (send_image(0, &image, NULL) != 0) {
      if (image.data) api.destroy_image(&image);
      break;
    }
    if (image.data) api.destroy_image(&image);
  }

  api.destroy(reader);
  dlclose(api.lib);
  close(protocol_fd);
  return 0;
}
