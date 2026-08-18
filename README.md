# 医疗数据治理及数字病理全生命周期上报平台 v0.3.0

面向医院信息化场景的可运行 MVP，覆盖医疗数据治理、数字切片全生命周期、文件版本与备份、病理数据和真实切片一键上报，并新增可独立运行的 Go 多格式切片解析服务。

## 系统架构

```text
Browser -> Nginx -> Vue 3 frontend
                 -> Spring Boot API -> MySQL
                                    -> MinIO
                                    -> slide-worker -> SVS / OpenSlide
                                                    -> Vendor formats / Go Parser
                                    -> HOT / ARCHIVE / BACKUP storage targets
                                    -> Mock/HTTP/File report receiver
```

后端保持单体应用，Python Worker 是统一解析门面，厂家格式的纯 Go 算法和可选 SDK 边界隔离在 `go-parser`；部署只依赖 Docker Compose，不包含消息队列、缓存集群或额外监控系统。

## 技术栈

- 前端：Vue 3、Vite、TypeScript、Element Plus、Pinia、Vue Router、Axios、OpenSeadragon
- 后端：Java 21、Spring Boot 3.4、Maven、MyBatis Plus、Spring JDBC、Spring Scheduler、Apache POI
- 切片服务：Python 3.12、FastAPI、OpenSlide；Go 1.23、`net/http`
- 基础设施：MySQL 8.4、MinIO、Nginx、Docker Compose

## 目录

```text
backend/       Spring Boot API 与业务闭环
frontend/      Vue 3 管理端
slide-worker/  格式 Adapter、OpenSlide 元数据与 Tile
go-parser/     多格式 Parser、只读缓存解析与统一 Tile API
deploy/        MySQL 初始化和 Nginx 配置
docs/          架构、数据库、API、部署和进度
```

## 环境要求

- Docker Desktop / Docker Engine，支持 Compose v2
- 本地开发可选：Java 21、Maven 3.9、Node.js 24
- 建议可用内存 6 GB 以上；上传文件默认最大 10 GB

## 启动

```bash
docker compose up -d --build
docker compose ps
```

访问：

- 平台：[http://localhost:8088](http://localhost:8088)
- 后端直连：[http://localhost:8080/api/system/ping](http://localhost:8080/api/system/ping)
- MinIO Console：[http://localhost:9001](http://localhost:9001)
- Slide Worker API：[http://localhost:8000/docs](http://localhost:8000/docs)

Go Parser 仅在 Compose 内部监听 `8100`，不通过 Nginx 暴露。

停止服务：

```bash
docker compose down
```

保留数据卷即可保留数据库、对象和上报文件。生产或共享环境必须从 `.env.example` 创建自己的 `.env`，替换所有密码和 `APP_SECRET`。

## 默认账号与角色

- `admin / Admin@123`：`ADMIN`
- `operator / Operator@123`：`OPERATOR`
- `viewer / Viewer@123`：`VIEWER`

密码使用 BCrypt 保存；医院数据源密码使用 AES-GCM 加密，列表 API 不返回密文。

## Mock 数据与演示流程

首次启动会创建 `mock_hospital` schema，包含 Mock HIS、EMR、LIS 表，并预置：

- 张三：性别源值 `1`，年龄 `45`
- 异常示例：年龄 `235`
- 就诊、诊断和检验样例各一条

演示流程：

1. 登录后进入“数据源管理”，测试 `Mock HIS` 并执行患者采集任务。
2. 在“医疗数据”查看性别已由 `1` 转换为 `M`。
3. 在“数据质量”查看“患者年龄超出允许范围”，将 `235` 修改为 `35` 并重新校验。
4. 在“数字切片”上传 SVS；文件进入 `pathology-original`，OpenSlide 解析后状态变为 `READY`。
5. 点击“查看”，使用 OpenSeadragon 缩放、拖动、全屏和缩略图导航。
6. 在“数字切片”把 READY 切片真实复制到 ARCHIVE 目标，核对对象、大小和 MD5。
7. 在“文件管理”上传 V2 并执行 HOT 到 BACKUP 的真实复制。
8. 在“数据上报”选择病例，生成包含患者、就诊、诊断、病理数据和实际 SVS 的 ZIP。
9. 打开“模拟失败”后上报，观察告警和 1/5/30 分钟退避重试；恢复 HTTP 200 后人工重报。

公开的小体积 SVS 测试文件可从 OpenSlide Test Data 使用，例如 `CMU-1-Small-Region.svs`。

## 切片格式支持

| 格式 | Engine | 状态 | 说明 |
|---|---|---|---|
| SVS | OpenSlide | `AVAILABLE` | 真实 metadata、thumbnail、Tile 和浏览器验收 |
| KFB / TMAP / MDSX / DMETRIX / FENLAN / ZYP | Go Native | `TEST_DATA_REQUIRED` | 已构建和完成安全失败测试，缺真实厂商样本 |
| SDPC | Go Native | `DECODER_REQUIRED` | JPEG/BMP 路径可构建；HEVC 缺 decoder，且缺真实样本 |
| CSP | Go CGO | `SDK_BUNDLED` | 原输入含 SDK，但无再分发许可，默认构建隔离 |
| HWP / TRON | Vendor SDK | `SDK_REQUIRED` | 缺 Linux SDK 与真实样本 |

代码存在不等于 `AVAILABLE`。只有真实文件完成 metadata、thumbnail、多个层级/位置 Tile 和 OpenSeadragon 阅片后才会升级状态。

## 当前限制

- MySQL 数据库 Connector 已完整跑通；SQL Server、Oracle、PostgreSQL、达梦保留 JDBC 配置入口，需部署对应驱动。
- HTTP API 与 FILE 可作为数据源配置；MVP 自动增量采集的验收链路以 MySQL Connector 为准。
- Token 存储在应用内存，服务重启后需重新登录；v0.2 提供 ADMIN/OPERATOR/AUDITOR/VIEWER 轻量 RBAC 和后端权限校验。
- 不提供 MPI、FHIR/DICOM Server、病理 AI、消息队列和复杂工作流。
- 大切片首次读取会下载到 Worker 持久缓存；Go Parser 只读共享缓存，并在进程内保存最多 128 个、TTL 30 分钟的 Parser 实例。

升级既有数据时不清理卷，应用启动会执行幂等字段升级并保留历史 bucket/object key。更多信息见 [部署文档](docs/deployment.md)、[API 文档](docs/api.md)、[v0.3 Parser 架构](docs/v0.3-parser-architecture.md)、[v0.3 验收](docs/v0.3-acceptance.md) 和 [当前进度](docs/progress.md)。
