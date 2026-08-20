# API 概览

所有业务 API 以 `/api` 开头，JSON 响应为：

```json
{ "code": 0, "message": "success", "data": {} }
```

除登录、ping 和 Mock 接收端外，请求需携带 `Authorization: Bearer <token>`。

## 主要端点

- 认证：`POST /api/auth/login`、`GET /api/auth/me`、`GET /api/auth/permissions`、`POST /api/auth/logout`
- 数据源：`GET/POST /api/data-sources`、`PUT /api/data-sources/{id}`、`POST /api/data-sources/{id}/test`
- 采集：`GET/POST /api/collect-tasks`、`POST /api/collect-tasks/{id}/execute`、`GET /api/collect-logs`
- 医疗数据：`GET/POST /api/medical-data/{type}`、`PUT /api/medical-data/{type}/{id}`
- 治理配置：`/api/governance/mapping-templates`、`mapping-fields`、`dictionaries`、`dictionary-items`、`validation-rules`
- 质量异常：`GET /api/validation-errors`、`PUT /{id}/value`、`POST /{id}/revalidate`、`POST /{id}/ignore`
- 基础数据：`GET /api/basic-data/export/{dictType}`、`POST /api/basic-data/import/{dictType}`（CSV/XLSX）
- 切片：`GET /api/slides`（多条件）、`POST /upload`、`GET /{id}/download`、`PUT /{id}/rename`、`DELETE /{id}`、`POST /{id}/analyze`、`GET /{id}/tiles/{level}/{x}/{y}`
- 归档：`GET /api/archive/tasks`、`GET/POST /api/archive/policies`、`POST /api/slides/{id}/archive`
- 存储：`GET/POST /api/storage-targets`、`PUT /{id}`、`POST /{id}/test`
- 文件：`GET /api/files`、`POST /upload`、`GET/POST /{id}/versions`、`GET /{id}/download`、`POST /batch/download`、`POST /batch/archive`、`DELETE /batch`
- 备份：`GET /api/backups/tasks`、`POST /api/backups/files/{fileId}`、`GET/POST /api/backups/policies`
- 上报：`GET/POST /api/report-batches`、`GET /pending-cases`、`POST /{id}/send`、`GET /{id}/download`；生成请求可含 `caseIds`、`templateId`
- 上报计划：`GET/POST /api/report-plans`、`PUT/DELETE /{id}`、`POST /{id}/run`
- 监控：`GET /api/system/dashboard`、`GET /health`、`GET /metrics`、`GET /adapters`
- 权限：`GET /api/system/roles`、`GET /permissions`、`PUT /roles/{id}/permissions`
- 告警：`GET /api/alerts/events`、`GET/POST /rules`、`POST /events/{id}/acknowledge`、`POST /events/{id}/close`

关键权限码包括 `SLIDE_VIEW/UPLOAD/DOWNLOAD/RENAME/DELETE/ARCHIVE`、`DATA_VIEW/EDIT`、`QUALITY_MANAGE`、`REPORT_GENERATE/SEND`、`FILE_MANAGE`、`DICT_MANAGE`、`USER_MANAGE`、`SYSTEM_CONFIG`、`LOG_VIEW`、`MONITOR_VIEW`。无权限时返回 HTTP 403。

Mock 接收端：`POST /api/mock-report/receive`；通过 `PUT /api/mock-report/mode` 的 `{ "fail": true }` 切换 HTTP 500 模式。
