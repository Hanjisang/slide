# 部署说明

## Compose 服务

| 服务 | 端口 | 用途 |
|---|---:|---|
| nginx | 8088 | 统一入口 |
| frontend | 内部 80 | Vue 静态站点 |
| backend | 8080 | Spring Boot API |
| slide-worker | 8000 | FastAPI/OpenSlide |
| mysql | 3306 | 业务与 Mock 数据 |
| minio | 9000 / 9001 | S3 API / Console |

## 启动与检查

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f backend slide-worker
```

健康检查：

```bash
curl http://localhost:8080/api/system/ping
curl http://localhost:8000/health
```

## 配置

复制 `.env.example` 为 `.env` 并替换：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`APP_SECRET`。如更改 `APP_SECRET`，已经加密的数据源密码需要重新录入。

持久卷包括 MySQL、MinIO、worker 切片缓存和上报文件。Nginx 已将最大请求体设置为 10 GB，并关闭 API 请求缓冲以支持大切片上传。

## v0.2 存储目标

首次启动创建三个 S3 目标：HOT=`pathology-original`、ARCHIVE=`pathology-archive`、BACKUP=`pathology-backup`。凭据使用 `APP_SECRET` 加密入库，API 不返回密文。S3 兼容后端无法提供总容量时 UI 显示 `UNKNOWN`。

## 就地升级

从 v0.1 升级时直接构建新镜像，不执行 `docker compose down -v`：

```bash
git checkout feature/v0.2-upgrade
docker compose up -d --build
docker compose logs backend --tail 100
```

后端日志出现 `Database schema is ready for v0.2.0` 表示幂等字段升级完成。然后检查 `/api/system/health`、历史切片列表和 MinIO 原 bucket。生产升级前仍应对 MySQL 与 MinIO 卷做平台级快照。

后端镜像产物为 `medical-report-platform-0.2.0.jar`，目标平台为 Linux amd64；Compose 不依赖 ARM64 镜像。
