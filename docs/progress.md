# 实施进度

更新时间：2026-08-30

## v0.3.0 多格式真实切片验收

- 当前分支：`feature/v0.3-multiformat-parser`，未合并 `main`。
- P23-P34：独立 Go Parser、Worker 门面、七服务 Compose、KFB/TMAP/MDSX/DMETRIX/FENLAN/ZYP 纯 Go 路径和 SDPC FFmpeg HEVC 路径均已工程化。
- P36：盘点 12 份真实文件，覆盖全部 11 种目标格式；样本只使用匿名别名且不进入 Git。
- SVS、KFB、TMAP、MDSX、DMETRIX、FENLAN、ZYP、SDPC 已通过真实 metadata、thumbnail、计划/随机 Tile、5/10 并发和 100 Tile 稳定性验证。
- 新增 `verify-slide` 统一验证命令，可扫描目录并输出匿名 JSON 证据。
- 修复 SVS Worker metadata 缺少 `READY` 协议字段、MDSX 坐标轴校验，以及前端混合层级顺序/512 tileSize 处理。
- KFB/TMAP/MDSX/ZYP 已从 `TEST_DATA_REQUIRED` 升级为 `AVAILABLE`。
- FINAL 开发收口：CSP 纯 Go 分段读取 parser、HWP/TRON 动态 SDK adapter、缓存原生句柄释放和 glibc 容器集成已完成。
- HWP、TRON 与 CSP 已完成真实切片复验：HWP 7 层、TRON 8 层稀疏瓦片、CSP 10 层均通过，三者状态均为 `AVAILABLE`。

## P36 主要结果

| Format | Samples | Metadata | Planned tiles | Random | 5/10 concurrent | Stability | Status |
|---|---:|---|---:|---:|---|---:|---|
| SVS | 1 | 31619×23152 / 3 | 9/9 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| KFB | 1 | 28034×27778 / 8 | 7/7 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| TMAP | 1 | 69712×21329 / 6 | 9/9 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| MDSX | 1 | 82767×163449 / 10 | 7/7 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| DMETRIX | 1 | 50978×57093 / 9 | 7/7 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| FENLAN | 1 | 3308×2847 / 2 | 7/7 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| ZYP | 1 | 32768×31232 / 10 | 7/7 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| SDPC | 1 | 83328×91392 / 8 | 9/9 | 20/20 | 5/5, 10/10 | 100/100 | `AVAILABLE` |
| HWP | 1/2 compatible | 52053×11520 / 7 | 23/23 | 用户复验通过 | 已完成本地链路复验 | 浏览器通过 | `AVAILABLE` |
| TRON | 1 | 73800×83232 / 8 | 8/8 | 用户复验通过 | 本地链路复验通过 | 浏览器通过 | `AVAILABLE` |
| CSP | 1 | 59136×45824 / 10 | 10/10 | 用户复验通过 | 本地链路复验通过 | 浏览器通过 | `AVAILABLE` |

TMAP/ZYP 的部分抽样 Tile 被图像哨兵标为 `ALL_WHITE`，请求与解码均成功；这是背景内容告警，不计解析失败。SDPC 因每 Tile 启动 FFmpeg 子进程，性能明显低于纯 Go JPEG/BMP 路径，详见统一验收报告。

## 自动化与构建

- Go：`go test ./...` 通过；Dockerfile 在 Debian/glibc、`CGO_ENABLED=1` 下执行全包测试并构建 `go-parser`、`verify-slide`。
- Worker：`pytest` 5 passed，包含 OpenSlide `READY` 协议回归。
- Frontend：TypeScript 检查与 Vite production build 通过；仅保留已有 bundle-size warning。
- Compose：MySQL、MinIO、Go Parser、Slide Worker、Backend、Frontend、Nginx 七服务已完成 no-cache 构建和健康启动；验收期间宿主机系统盘耗尽导致 Docker Desktop 控制面/MySQL 停止，属于宿主环境事件，不是 parser panic。

## v0.2.0 基线

v0.2 的多存储目标、真实归档/备份、文件版本、RBAC、数据质量、病理 ZIP 和上报重试仍由既有 Maven/前端回归覆盖。本次未合并 `main`、未创建 tag，也未把真实切片或厂家 SDK 提交仓库。

## 后续依赖

- HWP：当前本地 Linux SDK 的兼容真实样本已验收通过；状态绑定该 SDK/ABI，另一份生成测试件仍不兼容。
- TRON：当前 Linux SDK/ABI 已验收通过；私有 vendor 镜像已内置 SDK，厂家文件仍不进入 Git，分发许可需由交付环境确认。
- CSP：纯 Go parser 已基于公开 OpenCsp 格式完成并通过真实样本验收，不依赖厂家 SDK。
- SDPC：可优化为常驻解码进程或批处理，降低 FFmpeg 子进程启动开销。
- 正式卫健委接口、SQL Server/Oracle/PostgreSQL/达梦仍需目标协议、证书、驱动和联调环境。
