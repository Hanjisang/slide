# API 概览

所有业务 API 以 `/api` 开头，JSON 响应为：

```json
{ "code": 0, "message": "success", "data": {} }
```

除登录、ping 和 Mock 接收端外，请求需携带 `Authorization: Bearer <token>`。

## 主要端点

- 认证：`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`
- 数据源：`GET/POST /api/data-sources`、`PUT /api/data-sources/{id}`、`POST /api/data-sources/{id}/test`
- 采集：`GET/POST /api/collect-tasks`、`POST /api/collect-tasks/{id}/execute`、`GET /api/collect-logs`
- 医疗数据：`GET/POST /api/medical-data/{type}`、`PUT /api/medical-data/{type}/{id}`
- 治理配置：`/api/governance/mapping-templates`、`mapping-fields`、`dictionaries`、`dictionary-items`、`validation-rules`
- 质量异常：`GET /api/validation-errors`、`PUT /{id}/value`、`POST /{id}/revalidate`、`POST /{id}/ignore`
- 切片：`GET /api/slides`、`POST /api/slides/upload`、`POST /api/slides/{id}/analyze`、`GET /api/slides/{id}/tiles/{level}/{x}/{y}`
- 上报：`GET/POST /api/report-batches`、`POST /api/report-batches/{id}/send`、`GET /api/report-batches/{id}/download`
- 监控：`GET /api/system/dashboard`、`GET /api/system/health`、`GET /api/system/adapters`

Mock 接收端：`POST /api/mock-report/receive`；通过 `PUT /api/mock-report/mode` 的 `{ "fail": true }` 切换 HTTP 500 模式。

