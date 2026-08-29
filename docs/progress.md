# 实施进度

更新时间：2026-08-29

## v0.3.0 多格式真实切片验收

- 当前分支：`feature/v0.3-multiformat-parser`，未合并 `main`。
- P23-P34：独立 Go Parser、Worker 门面、七服务 Compose、KFB/TMAP/MDSX/DMETRIX/FENLAN/ZYP 纯 Go 路径和 SDPC FFmpeg HEVC 路径均已工程化。
- P36：盘点 12 份真实文件，覆盖全部 11 种目标格式；样本只使用匿名别名且不进入 Git。
- SVS、KFB、TMAP、MDSX、DMETRIX、FENLAN、ZYP、SDPC 已通过真实 metadata、thumbnail、计划/随机 Tile、5/10 并发和 100 Tile 稳定性验证。
- 新增 `verify-slide` 统一验证命令，可扫描目录并输出匿名 JSON 证据。
- 修复 SVS Worker metadata 缺少 `READY` 协议字段、MDSX 坐标轴校验，以及前端混合层级顺序/512 tileSize 处理。
- KFB/TMAP/MDSX/ZYP 已从 `TEST_DATA_REQUIRED` 升级为 `AVAILABLE`。
- HWP/TRON 的本地 SDK 是依赖可解析的 Linux amd64 ELF，但 v0.3 没有已验证的 adapter/ABI，状态为 `COMPATIBILITY_REQUIRED`。
- CSP 有真实样本，但无可确认授权的 SDK 和运行许可，状态为 `LICENSE_REQUIRED`。

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
| HWP | 2 | blocked before parser | — | — | — | — | `COMPATIBILITY_REQUIRED` |
| TRON | 1 | blocked before parser | — | — | — | — | `COMPATIBILITY_REQUIRED` |
| CSP | 1 | blocked before parser | — | — | — | — | `LICENSE_REQUIRED` |

TMAP/ZYP 的部分抽样 Tile 被图像哨兵标为 `ALL_WHITE`，请求与解码均成功；这是背景内容告警，不计解析失败。SDPC 因每 Tile 启动 FFmpeg 子进程，性能明显低于纯 Go JPEG/BMP 路径，详见统一验收报告。

## 自动化与构建

- Go：`go test ./...` 通过；Dockerfile 在 `CGO_ENABLED=0` 下执行全包测试并构建 `go-parser`、`verify-slide`。
- Worker：`pytest` 5 passed，包含 OpenSlide `READY` 协议回归。
- Frontend：TypeScript 检查与 Vite production build 通过；仅保留已有 bundle-size warning。
- Compose：MySQL、MinIO、Go Parser、Slide Worker、Backend、Frontend、Nginx 七服务已完成 no-cache 构建和健康启动；验收期间宿主机系统盘耗尽导致 Docker Desktop 控制面/MySQL 停止，属于宿主环境事件，不是 parser panic。

## v0.2.0 基线

v0.2 的多存储目标、真实归档/备份、文件版本、RBAC、数据质量、病理 ZIP 和上报重试仍由既有 Maven/前端回归覆盖。本次未合并 `main`、未创建 tag，也未把真实切片或厂家 SDK 提交仓库。

## 后续依赖

- HWP/TRON：需要厂家确认的函数签名、初始化/释放顺序、线程模型、匹配 SDK 版本和许可，再在隔离构建中实现并验收 adapter；不能仅因 `.so` 存在就标为可用。
- CSP：先取得合法 SDK、运行许可证和再分发边界，再进行 CGO 隔离集成与真实文件验收。
- SDPC：可优化为常驻解码进程或批处理，降低 FFmpeg 子进程启动开销。
- 正式卫健委接口、SQL Server/Oracle/PostgreSQL/达梦仍需目标协议、证书、驱动和联调环境。
