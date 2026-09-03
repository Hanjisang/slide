# Go Multi-format Slide Parser

内部 HTTP 服务，为 Python Slide Worker 提供厂家数字切片解析能力。镜像为 Linux amd64/glibc + CGO，不直接访问 MinIO，也不对公网暴露。普通 `runtime` target 不含厂家 SDK；私有发布使用 `vendor` target，将 HWP/TRON Linux SDK 写入镜像。

## 支持状态

| 格式 | Engine | 状态 | 说明 |
|---|---|---|---|
| KFB / TMAP / MDSX / DMETRIX / FENLAN / ZYP | Go Native | `AVAILABLE` | 真实样本已完成 metadata、附件、多层级/边缘/随机 Tile、并发和稳定性验证 |
| SDPC | Go Native + FFmpeg | `AVAILABLE` | 真实 HEVC Annex-B Tile 已完成多层级、多坐标和边缘验证 |
| CSP | Go Native | `AVAILABLE` | 真实样本完成 10 层 metadata、thumbnail、Tile 和浏览器人工验收 |
| HWP | Vendor SDK + native helper | `AVAILABLE` | 当前 SDK 的兼容真实样本已完成 7 层 metadata、附件、Tile 和浏览器人工复验 |
| TRON | Vendor SDK Runtime | `AVAILABLE` | 当前本地 SDK 的真实样本完成 8 层 metadata、稀疏 Tile、thumbnail 和浏览器人工验收 |

SVS 不由本服务处理，继续由 Python Worker 的 OpenSlideAdapter 解析。

## 构建与测试

```bash
go test ./...
CGO_ENABLED=1 go build -o go-parser ./cmd/server
docker build --target runtime -t medical-go-parser:0.3.0 .
docker compose build go-parser-vendor
```

Dockerfile 会先执行全包测试，再用非 root 用户启动。服务监听 `:8100`，缓存根目录来自 `SLIDE_CACHE_DIR`，默认 `/data/slides`。CSP 不依赖 CGO；启用 CGO 是为了运行时调用 HWP/TRON Linux SDK。

## API

```text
GET  /health
GET  /api/formats
POST /api/slides/{id}/analyze
GET  /api/slides/{id}/thumbnail
GET  /api/slides/{id}/label
GET  /api/slides/{id}/macro
GET  /api/slides/{id}/tiles/{level}/{x}/{y}
```

服务只从 `/data/slides/{id}` 选择切片文件。客户端不能提交任意本地路径。Tile 返回稳定 JPEG；默认 256 x 256，MDSX 按源 512 block 返回 512 x 512，边缘以白色补齐。`analyze` 的每层元数据包含 `tileSize`，前端据此建立 OpenSeadragon tile source。

## 真实样本验证工具

Docker 镜像内置 `verify-slide`，可对外部只读样本目录做匿名、可重复的统一验证：

```bash
verify-slide --dir /samples --all --random 20 --performance 10 --concurrency 5,10 --stability 100 --json-out /tmp/result.json
```

工具检查格式识别、metadata、thumbnail/label/macro、低/中/高/边缘 Tile、随机 Tile、性能、并发和稳定性；错误会移除真实路径和原文件名。SVS 仍由 Worker/OpenSlide 单独验证。

## 本地运行

```bash
docker run --rm -p 8100:8100 \
  -e SLIDE_CACHE_DIR=/data/slides \
  -v /absolute/slide-cache:/data/slides:ro \
  medical-go-parser-vendor:0.3.0
```

每个 ID 目录放一个待解析文件，例如 `/data/slides/42/example.kfb`，然后调用 `POST /api/slides/42/analyze`。HWP 在 Compose 中通过隔离 sidecar 和原生 helper 调用 SDK，避免 SDK 故障导致 Go parser 退出。

## 厂家 SDK

`vendor-libs/` 与本地 `vendor-libs-local/` 均不得提交。私有 `go-parser-vendor` 镜像在构建时通过 Compose named build context 仅复制 `hwp/libhwp_sdk.so` 与 `tron/libtronc.so`，不复制 Windows DLL 或厂家头文件；普通 `go-parser` 镜像仍不含 SDK。HWP 原生 helper 与 TRON runtime adapter 分别从镜像内 `HWP_SDK_PATH`、`TRON_SDK_PATH` 动态加载。CSP 为纯 Go，不需要厂家 SDK。OpenCsp 来源与许可见仓库根目录 `THIRD_PARTY_NOTICES.md`。

## 测试数据

大型切片不得提交仓库。可将测试数据放在被忽略的 `data/`、外部目录或专用只读 volume。本轮样本与结果见 `docs/v0.3-real-sample-inventory.md`、`docs/v0.3-real-slide-acceptance.md`。
