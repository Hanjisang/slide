# 部署说明

## Compose 服务

| 服务 | 端口 | 用途 |
|---|---:|---|
| nginx | 8088 | 统一入口 |
| frontend | 内部 80 | Vue 静态站点 |
| backend | 8080 | Spring Boot API |
| slide-worker | 8000 | FastAPI/OpenSlide |
| go-parser | 内部 8100 | Go 多格式切片解析 |
| mysql | 3306 | 业务与 Mock 数据 |
| minio | 9000 / 9001 | S3 API / Console |

## 启动与检查

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f backend slide-worker go-parser
```

健康检查：

```bash
curl http://localhost:8080/api/system/ping
curl http://localhost:8000/health
docker compose exec go-parser wget -qO- http://localhost:8100/health
```

## 配置

复制 `.env.example` 为 `.env` 并替换：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`APP_SECRET`。如更改 `APP_SECRET`，已经加密的数据源密码需要重新录入。

持久卷包括 MySQL、MinIO、Worker 切片缓存和上报文件。`slide-worker` 对 `slide_cache` 读写，`go-parser` 只读挂载；Go Parser 不访问 MinIO。Nginx 已将最大请求体设置为 10 GB，并关闭 API 请求缓冲以支持大切片上传。

## v0.3 Parser 配置

- `GO_PARSER_URL=http://go-parser:8100`：Worker 调用地址。
- `SLIDE_CACHE_DIR=/data/slides`：Go 固定缓存根目录。
- Go formats/health 超时 5 秒，metadata/thumbnail 30 秒，tile 15 秒。
- Go Parser 健康检查失败不会阻止 Worker 启动；SVS 仍由 OpenSlide 处理。

默认镜像不含任何厂家 `.so/.dll`，并以 `CGO_ENABLED=1` 构建。获合法授权的 TRON/HWP SDK 可放入被忽略的 `vendor-libs-local/`，由 Compose 只读挂载并通过环境变量指定；真实文件验收通过前不会标记 `AVAILABLE`。

## v0.2 存储目标

首次启动创建三个 S3 目标：HOT=`pathology-original`、ARCHIVE=`pathology-archive`、BACKUP=`pathology-backup`。凭据使用 `APP_SECRET` 加密入库，API 不返回密文。S3 兼容后端无法提供总容量时 UI 显示 `UNKNOWN`。

## 就地升级

从 v0.1 升级时直接构建新镜像，不执行 `docker compose down -v`：

```bash
git checkout feature/v0.3-multiformat-parser
docker compose up -d --build
docker compose logs backend --tail 100
```

后端日志出现 `Database schema is ready for v0.2.0` 表示既有幂等字段升级完成。然后检查 `/api/system/health` 中 `goParser`、历史切片列表和 MinIO 原 bucket。生产升级前仍应对 MySQL 与 MinIO 卷做平台级快照。

后端镜像产物为 `medical-report-platform-0.2.0.jar`，目标平台为 Linux amd64；Compose 不依赖 ARM64 镜像。
