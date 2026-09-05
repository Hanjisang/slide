# 一键推送本地镜像到公司仓库

脚本适用于 Windows + Docker Desktop。默认目标来自当前环境：

```text
10.25.13.206:5000/custom-develop
```

脚本不会保存账号、密码或 token。

## 使用方式

1. 先连接公司 VPN，并确认 Docker Desktop 正在运行。
2. 在资源管理器中双击 `scripts/push-images-to-registry.cmd`。
3. 输入 `YES` 确认推送。

如果尚未登录仓库，可在 PowerShell 中执行：

```powershell
.\scripts\push-images-to-registry.ps1 -Login
```

Docker 会交互式读取账号和密码，凭据由 Docker Desktop 管理，不会写入项目文件。

## 常用参数

```powershell
# 推送不可变版本标签，同时更新 latest
.\scripts\push-images-to-registry.ps1 -Tag 20260905-1200 -PushLatest

# 使用另一个仓库地址或命名空间
.\scripts\push-images-to-registry.ps1 -Registry 10.25.13.206:5000 -Namespace custom-develop

# 不推送厂商 SDK Parser 镜像
.\scripts\push-images-to-registry.ps1 -SkipVendor
```

默认推送：backend、frontend、go-parser、slide-worker；本地存在时还会推送 go-parser-vendor。推送前脚本会检查 VPN 到仓库的 TCP 连通性、本地镜像是否存在，并列出完整目标清单，只有输入 `YES` 才会执行。

## CI/部署侧镜像名

```text
10.25.13.206:5000/custom-develop/medical-report-mvp-backend:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-frontend:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-go-parser:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-slide-worker:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-go-parser-vendor:<tag>
```

其中 vendor 镜像包含厂商 Parser 依赖；没有对应依赖或仓库不接收该镜像时，可使用 `-SkipVendor`。
