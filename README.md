# 医疗数据及数字病理上报平台 MVP

面向医院信息化场景的最小可运行平台，覆盖医院数据采集、字段映射、字典标准化、质量校验、异常人工修正、数字病理切片管理、在线阅片和数据上报闭环。

## 系统架构

```text
Browser -> Nginx -> Vue 3 frontend
                 -> Spring Boot API -> MySQL
                                    -> MinIO
                                    -> slide-worker -> OpenSlide
                                    -> Mock/HTTP/File report receiver
```

后端保持单体应用，厂商切片 SDK 统一隔离在 `slide-worker`；部署只依赖 Docker Compose，不包含消息队列、缓存集群或额外监控系统。

## 技术栈

- 前端：Vue 3、Vite、TypeScript、Element Plus、Pinia、Vue Router、Axios、OpenSeadragon
- 后端：Java 21、Spring Boot 3.4、Maven、MyBatis Plus、Spring JDBC、Spring Scheduler
- 切片服务：Python 3.12、FastAPI、OpenSlide
- 基础设施：MySQL 8.4、MinIO、Nginx、Docker Compose

## 目录

```text
backend/       Spring Boot API 与业务闭环
frontend/      Vue 3 管理端
slide-worker/  格式 Adapter、OpenSlide 元数据与 Tile
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

停止服务：

```bash
docker compose down
```

保留数据卷即可保留数据库、对象和上报文件。生产或共享环境必须从 `.env.example` 创建自己的 `.env`，替换所有密码和 `APP_SECRET`。

## 默认账号

- 用户名：`admin`
- 密码：`Admin@123`
- 角色：`ADMIN`

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
6. 在“数据上报”生成 JSON/XML/CSV/ZIP 批次并发送到 Mock 接收端。
7. 打开“模拟失败”后再次上报，观察失败记录和 1/5/30 分钟自动重试；也可人工重报。

公开的小体积 SVS 测试文件可从 OpenSlide Test Data 使用，例如 `CMU-1-Small-Region.svs`。

## 切片格式支持

| 格式 | Adapter | 状态 |
|---|---|---|
| SVS | OpenSlideAdapter | `AVAILABLE`，真实元数据、缩略图和 Tile |
| KFB / SDPC / TRON / MDSX / TMAP | VendorAdapter | `ADAPTER_READY` / `SDK_REQUIRED` |
| DMETRIX / FENLAN / ZYP / HWP / CSP | VendorAdapter | `ADAPTER_READY` / `SDK_REQUIRED` |

没有厂商 SDK 时系统只识别格式并明确返回 `SDK_NOT_AVAILABLE`，不会伪造解析结果。

## 当前限制

- MySQL 数据库 Connector 已完整跑通；SQL Server、Oracle、PostgreSQL、达梦保留 JDBC 配置入口，需部署对应驱动。
- HTTP API 与 FILE 可作为数据源配置；MVP 自动增量采集的验收链路以 MySQL Connector 为准。
- Token 存储在应用内存，服务重启后需重新登录；不提供复杂 RBAC。
- 不提供 MPI、FHIR/DICOM Server、病理 AI、消息队列和复杂工作流。
- 大切片首次读取会下载到 worker 持久缓存；生产环境可在后续增加缓存淘汰策略。

更多信息见 [部署文档](docs/deployment.md)、[API 文档](docs/api.md) 和 [当前进度](docs/progress.md)。

