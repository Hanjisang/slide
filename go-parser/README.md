# Go Multi-format Slide Parser

内部 HTTP 服务，为 Python Slide Worker 提供厂家数字切片解析能力。默认构建为 Linux amd64/CGO disabled，不直接访问 MinIO，也不对公网暴露。

## 支持状态

| 格式 | Engine | 状态 | 说明 |
|---|---|---|---|
| KFB / TMAP / MDSX / ZYP | Go Native | `TEST_DATA_REQUIRED` | 已构建和做损坏输入测试，缺真实厂商文件 L3-L5 验收 |
| DMETRIX / FENLAN | Go Native | `AVAILABLE` | 真实样本已完成 metadata、thumbnail、多层级/边缘 Tile 验证 |
| SDPC | Go Native + FFmpeg | `AVAILABLE` | 真实 HEVC Annex-B Tile 已完成多层级、多坐标和边缘验证 |
| CSP | Go CGO | `SDK_BUNDLED` | 原输入含 SDK，但无再分发许可，默认构建不包含 |
| HWP / TRON | Vendor SDK | `SDK_REQUIRED` | 缺运行所需 Linux SDK |

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

服务只从 `/data/slides/{id}` 选择切片文件。客户端不能提交任意本地路径。Tile 始终返回 256 x 256 JPEG，边缘以白色补齐。

## 本地运行

```bash
docker run --rm -p 8100:8100 \
  -e SLIDE_CACHE_DIR=/data/slides \
  -v /absolute/slide-cache:/data/slides:ro \
  medical-go-parser:0.3.0
```

每个 ID 目录放一个待解析文件，例如 `/data/slides/42/example.kfb`，然后调用 `POST /api/slides/42/analyze`。

## 厂家 SDK

`vendor-libs/` 已被 Git 忽略，仅用于取得明确授权后的本机实验。SDPC HEVC 默认使用 Alpine 仓库中的 FFmpeg 标准解码器，不链接厂商 `.so`；CSP/HWP/TRON 仍需合法授权、Linux amd64 库、受控 build tag/adapter。不得直接提交 `.so`、`.dll` 或厂家头文件。

## 测试数据

大型切片不得提交仓库。可将测试数据放在被忽略的 `data/`、外部目录或专用只读 volume。只有真实文件完成 metadata、thumbnail、多个层级/位置 Tile 和浏览器阅片后，能力状态才可改为 `AVAILABLE`。
