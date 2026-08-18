# 实施进度

更新时间：2026-08-18

## v0.2.0 已完成

- 当前分支：`feature/v0.2-upgrade`，未合并 `main`。
- P10-P13：SlideStorageService 拆分、多存储目标、切片管理、真实归档与上传时间策略完成。
- P14-P16：文件资产/版本、批量操作、备份、四角色轻量授权和后端 403 校验完成。
- P17-P19：UNIQUE/CROSS_FIELD/CROSS_RECORD、基础数据 CSV/XLSX、真实系统指标和告警闭环完成。
- P20：病理病例选择、实际 SVS ZIP、模板、手工/定时计划、文件中心登记和 1/5/30 分钟重试完成。
- P21：SVS 真实解析为 AVAILABLE；10 种厂商格式保留 SDK_REQUIRED。
- P22：Maven、前端、Docker、API、对象存储和浏览器验收完成。

## 实际测试

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

- KFB、SDPC、TRON、MDSX、TMAP、DMETRIX、FENLAN、ZYP、HWP、CSP 真实解析需要对应厂商商业 SDK，状态为 `EXTERNAL_DEPENDENCY / SDK_REQUIRED`。
- 正式卫健委接口协议、签名方式、证书和接收地址未提供；当前以可配置模板、HTTP/File Sender 和 Mock HTTP 端点验收。
- SQL Server、Oracle、PostgreSQL、达梦连接验收需要目标环境与 JDBC 驱动。

## 已知限制

- Token 为单实例内存会话，服务重启后需重新登录。
- S3 兼容接口不能可靠获取后端总容量时明确显示 `UNKNOWN`。
- 首屏依赖包仍有体积提示，不影响功能和运行。
