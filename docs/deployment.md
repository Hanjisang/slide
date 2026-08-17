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

