# 架构说明

## 设计目标

系统以完整闭环和最少基础设施为优先。业务 API 位于单个 Spring Boot 服务；切片格式 SDK 因运行时和许可证差异独立为 FastAPI worker。所有服务通过 Compose 内部网络连接。

## 数据链路

```text
Mock/医院 MySQL
 -> collect_task (last_sync_time)
 -> MappingEngine
 -> 字典转换与基础清洗
 -> 标准医疗表 (source_system + source_id 去重)
 -> ValidationService
 -> validation_error
 -> 人工修正 / 重新校验
 -> ReportPackageBuilder (患者 / 病理 / 病理+切片)
 -> ReportExporter
 -> ReportSender
 -> report_batch / report_record
```

## 切片链路

```text
上传 -> Spring Boot MD5/扩展名限制 -> MinIO pathology-original
     -> slide_file: PARSING -> slide-worker Adapter
     -> OpenSlide 元数据 -> slide_file: READY
Browser -> /api/slides/{id}/tiles/{level}/{x}/{y}
        -> Spring Boot 统一代理 -> slide-worker -> OpenSlide
```

## v0.2 局部重构

```text
SlideController -> SlideService
                -> SlideFileService
                -> SlideWorkerClient
                -> ArchiveService -> ArchiveTaskService

StorageProvider -> S3StorageProvider -> HOT / ARCHIVE / BACKUP storage_target
FileAssetService -> file_asset / file_version -> BackupService
ReportService -> ReportPackageBuilder -> ReportExporter -> ReportSender
```

归档和备份均执行真实对象复制、存在性/大小/MD5 校验。归档不删除 HOT 源对象；逻辑删除也不物理删除对象。生成的上报文件同时进入文件中心。

Spring Scheduler 承担上传时间归档、每日/每周备份、上报计划、失败重试和告警扫描，不引入 MQ、Quartz 或工作流。

`slide-worker` 将原始对象下载到持久缓存。Spring Boot 不加载任何厂商 DLL/SO/SDK。

## 安全边界

- BCrypt 保存平台用户密码。
- AES-GCM 保存外部数据源密码；密钥来自 `APP_SECRET`。
- 上传只允许声明的切片扩展名，文件名被净化并使用 UUID 对象键。
- 动态医疗表访问使用固定白名单；采集 SQL 只允许单条带 `:lastSyncTime` 的 SELECT。
- API 使用统一异常响应，不向前端返回数据库密码或堆栈。
- `@RequirePermission` 在 AuthInterceptor 中执行后端授权；前端菜单过滤只用于体验，不能代替服务端 403。
