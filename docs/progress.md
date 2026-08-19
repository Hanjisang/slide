# 实施进度

更新时间：2026-08-19

## v0.3.1 监控告警与可选 SDK

- 统一监控快照、基础服务/资源/存储/任务/队列告警规则、防抖去重和自动恢复。
- AppLayout 全局告警轮询、铃铛数量和 sessionStorage 通知去重。
- 已接收并解包 TRON/HWP Linux SDK 到忽略目录；HWP 接入 CGO 适配器，TRON 完成符号探测但缺公开 ABI。两者均缺真实样本，不能标记 `AVAILABLE`。

## v0.3.0 多格式解析已完成工程化

- 当前分支：`feature/v0.3-multiformat-parser`，基于完整 v0.2 提交 `982cd63`，未合并 `main`。
- P23：独立 `go-parser` module、FileStreamer、最小 types/utils、Registry、HTTP API、ParserCache 和 Docker 构建完成。
- P24：Python `GoParserAdapter`、动态能力发现、30/15 秒调用超时与 Go Down 降级完成；SVS 继续由 OpenSlide 处理。
- P25-P28：KFB、TMAP06/07、MDSX、DMETRIX、FENLAN、ZYP 的纯 Go 算法接入完成；因无真实厂商文件，状态严格保留 `TEST_DATA_REQUIRED`。
- P29：SDPC 结构/JPEG/BMP 路径完成，HEVC 隔离为 `DECODER_REQUIRED`；颜色校正和编码图片分配已加固。
- P30-P31：CSP 标记 `SDK_BUNDLED` 但因无再分发许可不进入默认构建；HWP/TRON 缺 SDK，均隔离且不影响服务启动。
- P32：七服务 Compose、系统监控、Go Parser 告警、11 格式能力矩阵、SVS 回归与 Go Down 场景完成。

## v0.2.0 基线已完成

- 当前分支：`feature/v0.2-upgrade`，未合并 `main`。
- P10-P13：SlideStorageService 拆分、多存储目标、切片管理、真实归档与上传时间策略完成。
- P14-P16：文件资产/版本、批量操作、备份、四角色轻量授权和后端 403 校验完成。
- P17-P19：UNIQUE/CROSS_FIELD/CROSS_RECORD、基础数据 CSV/XLSX、真实系统指标和告警闭环完成。
- P20：病理病例选择、实际 SVS ZIP、模板、手工/定时计划、文件中心登记和 1/5/30 分钟重试完成。
- P21：SVS 真实解析为 AVAILABLE；该能力在 v0.3 继续保留。
- P22：Maven、前端、Docker、API、对象存储和浏览器验收完成。

## v0.3 实际测试

- `go test ./...`：通过；覆盖 11 个顶层测试，包括 Streamer 越界、截断厂家文件、Registry 状态、HTTP、256 JPEG 和 SDPC 分配边界。
- `pytest`：4 passed；验证 Go 能力降级短缓存、固定缓存路径和 SVS 优先 OpenSlide。
- `mvn -q test`：7 个测试类通过；`npm run build` 通过，仅有非阻断 bundle size warning。
- Go Docker 镜像在 v0.3.1 的 `CGO_ENABLED=1` 下通过测试与构建。
- Docker Compose：MySQL、MinIO、go-parser、slide-worker、backend、frontend、nginx 七服务运行。
- Go health：`UP`、7 个已注册 Parser、`cgo=true`；Worker：`UP`、11 个 Adapter；Backend 监控显示 Go Parser `UP`。
- 真实 SVS：`CMU-1-Small-Region.svs`，1,938,955 bytes，2220 x 2967；metadata、thumbnail、两个 Tile 和 OpenSeadragon 通过。
- Go Down：Worker 保持 `UP`，SVS metadata/Tile 继续成功；Go 格式能力降级为 `PARSER_UNAVAILABLE`。
- 浏览器：11 格式能力矩阵和 Go Parser 监控正常；桌面与 390 x 844 无页面级横向溢出。

## v0.2 基线测试

- `mvn -q test`：7 个测试类通过，覆盖归档复制/MD5、备份、逻辑删除、权限 403、病理 ZIP 和 CSV BOM 往返。
- `npm run build`：通过；仅有非阻断的 bundle size warning。
- `docker compose up -d --build`：MySQL、MinIO、slide-worker、backend、frontend、nginx 六服务运行；健康检查通过。
- 真实 SVS：1,938,955 bytes，OpenSlide 返回 `2220 x 2967`、`AVAILABLE`、真实 thumbnail/tile；下载 MD5 为 `1ad6e35c9d17e4d85fb7e3143b328efe`。
- 真实归档：HOT 到 `pathology-archive`，源/目标大小和 MD5 一致，任务 100%，源对象保留。
- 文件版本/备份：V1/V2 对象同时存在；BACKUP 对象实际存在且 MD5 一致。
- 权限：VIEWER 查看切片 200，删除/归档/用户管理均 403；OPERATOR 上传、归档、上报通过。
- 校验：重复病理号返回业务异常；入院时间晚于出院时间生成 CROSS_FIELD 异常，并完成修正闭环。
- 病理包：ZIP 实际打开，含 manifest、患者/就诊/诊断/病理 JSON 与 2 张真实 SVS；生成物进入文件中心。
- HTTP：500 后生成告警，调度器实际重试并从 1 分钟推进到 5 分钟；恢复 200 后人工上报成功。
- 基础数据/监控：CSV 与 XLSX 往返 0 错误；CPU、内存、磁盘、JVM、存储目标和五类任务均为运行时数据。
- 浏览器：桌面与 390 x 844 视口通过，无横向溢出；八域菜单及四个核心聚合页正常。

## 外部依赖

- KFB、TMAP、MDSX、DMETRIX、FENLAN、ZYP 已不依赖厂家 SDK，但缺真实文件 L3-L5 验收，状态为 `TEST_DATA_REQUIRED`。
- SDPC 缺真实文件；HEVC 路径缺 `libDecodeHevc.so`，状态为 `DECODER_REQUIRED`。
- CSP 原输入带 SDK，但未提供再分发许可，公开构建不包含二进制；HWP/TRON 分别缺 `libhwp_sdk.so`、`libtronc.so`。
- 正式卫健委接口协议、签名方式、证书和接收地址未提供；当前以可配置模板、HTTP/File Sender 和 Mock HTTP 端点验收。
- SQL Server、Oracle、PostgreSQL、达梦连接验收需要目标环境与 JDBC 驱动。

## 已知限制

- Token 为单实例内存会话，服务重启后需重新登录。
- S3 兼容接口不能可靠获取后端总容量时明确显示 `UNKNOWN`。
- 首屏依赖包仍有体积提示，不影响功能和运行。
- 厂家格式未完成真实 metadata/thumbnail/多 Tile/浏览器验收前，不得标为 `AVAILABLE`。
