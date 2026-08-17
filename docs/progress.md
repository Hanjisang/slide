# 实施进度

更新时间：2026-08-17

## 已完成

- P0：backend、frontend、slide-worker、MySQL、MinIO、Nginx 与 Docker Compose 已实际构建启动。
- P1：登录、七个一级菜单、统一响应、统一异常、敏感配置加密、用户与系统配置。
- P2：患者、就诊、诊断、检验、检查、手术、用药标准表与 CRUD API/页面。
- P3：Mock HIS/LIS/EMR schema、MySQL 数据源、连接测试、`last_sync_time` 增量采集、日志和调度。
- P4：字段 Mapping、10 种转换类型、性别字典、`source_system + source_id` 去重。
- P5：8 种校验规则、自动异常、人工修改、处理日志、重新校验、忽略闭环。
- P6：MinIO 原始桶/缓存桶、格式识别、Adapter、SVS OpenSlide 真解析、元数据、统一 Tile、OpenSeadragon 阅片和归档。
- P7：JSON/XML/CSV/ZIP Exporter、HTTP/File Sender、批次/明细、Mock 接收、失败退避重试和人工重报。
- P8：首页统计、MySQL/MinIO/Slide Worker 健康、存储与业务队列监控。
- P9：已实测采集 2 条、字典 `1 -> M`、235 岁异常、修正 35 后通过、HTTP 成功/500/自动重试、真实 SVS Tile 与浏览器页面。

## 最终验收

- `mvn -q test`：通过，包含 JSON/XML/CSV/ZIP 导出文件结构校验。
- `npm run build`：通过，仅有非阻断的首屏依赖包体积提示。
- Docker Compose 六个服务均已启动，backend、MySQL、MinIO、slide-worker 健康检查通过。
- XML、CSV、ZIP 批次已通过 API 实际生成并使用 File Sender 发送到 `/data/reports/outbox`。
- 桌面端、390 x 844 移动端和 OpenSeadragon 真实 SVS 阅片已完成浏览器验收。

## 未完成 / 外部依赖

- KFB、SDPC、TRON、MDSX、TMAP、DMETRIX、FENLAN、ZYP、HWP、CSP：`ADAPTER_READY`，`SDK_REQUIRED`。
- SQL Server、Oracle、PostgreSQL、达梦需在实际环境提供并安装对应 JDBC 驱动后做连接验收。

## 当前问题

- 无阻断 MVP 演示的问题。
- 首屏依赖包体积仍可继续拆分，但不影响运行。
- 应用内存 Token 不适合多实例生产部署。

## 下一阶段

- 获取真实厂商 SDK 后在 worker 内实现对应 Adapter。
- 根据目标上报规范配置正式模板和接收端。
- 补充生产反向代理 TLS、备份、缓存淘汰与更细粒度审计策略。
