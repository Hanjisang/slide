# Go Multi-format Slide Parser

内部 HTTP 服务，为 Python Slide Worker 提供厂家数字切片解析能力。默认构建为 Linux amd64/CGO disabled，不直接访问 MinIO，也不对公网暴露。

## 支持状态

| 格式 | Engine | 状态 | 说明 |
|---|---|---|---|
| KFB / TMAP / MDSX / DMETRIX / FENLAN / ZYP | Go Native | `AVAILABLE` | 真实样本已完成 metadata、附件、多层级/边缘/随机 Tile、并发和稳定性验证 |
| SDPC | Go Native + FFmpeg | `AVAILABLE` | 真实 HEVC Annex-B Tile 已完成多层级、多坐标和边缘验证 |
| CSP | Go CGO | `LICENSE_REQUIRED` | 缺可确认授权的 SDK 与运行许可，默认构建不包含 |
| HWP / TRON | Vendor SDK | `COMPATIBILITY_REQUIRED` | 本地 SDK 是可链接的 Linux amd64 ELF，但 v0.3 没有匹配 adapter/ABI |

SVS 不由本服务处理，继续由 Python Worker 的 OpenSlideAdapter 解析。

## 构建与测试

```bash
go test ./...
CGO_ENABLED=0 go build -o go-parser ./cmd/server
docker build -t medical-go-parser:0.3.0 .
```

Dockerfile 会先执行全包测试，再用非 root 用户启动。服务监听 `:8100`，缓存根目录来自 `SLIDE_CACHE_DIR`，默认 `/data/slides`。

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
  medical-go-parser:0.3.0
```

每个 ID 目录放一个待解析文件，例如 `/data/slides/42/example.kfb`，然后调用 `POST /api/slides/42/analyze`。

## 厂家 SDK

`vendor-libs/` 与本地 `vendor-libs-local/` 均不得提交，只用于取得明确授权后的本机实验。SDPC HEVC 默认使用 Alpine 仓库中的 FFmpeg 标准解码器，不链接厂商 `.so`；CSP/HWP/TRON 仍需合法授权、匹配 adapter/ABI 和受控构建。不得直接提交 `.so`、`.dll` 或厂家头文件。

## 测试数据

大型切片不得提交仓库。可将测试数据放在被忽略的 `data/`、外部目录或专用只读 volume。本轮样本与结果见 `docs/v0.3-real-sample-inventory.md`、`docs/v0.3-real-slide-acceptance.md`。
