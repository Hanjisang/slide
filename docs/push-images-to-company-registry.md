# 一键推送本地镜像到公司仓库

脚本适用于 Windows + Docker Desktop。默认目标来自当前环境：

```text
10.25.13.206:5000/custom-develop
```

脚本不会保存账号、密码或 token。

## 使用方式

1. 先连接公司 VPN，并确认 Docker Desktop 正在运行。
2. 在项目根目录双击 `push-images-to-company-registry.bat`（也可以双击 `scripts/push-images-to-registry.bat`）。
3. 如果尚未登录公司仓库，选择 `Y`；否则选择 `N`。
4. 输入 `YES` 确认推送。

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

默认推送：backend、frontend、go-parser、slide-worker；本地存在时还会推送 go-parser-vendor。推送前脚本会检查 VPN 到仓库的 TCP 连通性、本地镜像是否存在，并列出完整目标清单，只有输入 `YES` 才会执行。`.bat` 完成后会停留在窗口中，方便查看成功或失败信息。

## 如何保证和本地一致

脚本不会重新构建镜像，而是直接使用当前本地已经验证过的镜像标签。对每个镜像，脚本会：

1. 记录本地镜像 ID。
2. `docker tag` 后确认目标标签仍指向同一个镜像 ID。
3. `docker push` 必须返回公司仓库确认的 manifest digest，并在推送后再次核对本地目标标签仍指向同一个镜像 ID；任一步失败都不会报告成功。

如果本地 Compose 服务正在运行，脚本还会先核对服务实际使用的镜像 ID；发现服务和待推送标签不是同一个镜像时会停止，避免把“未运行/未验收”的旧标签推到仓库。

因此，推送成功后，镜像中的代码、依赖和文件层与本地完全相同。建议使用不可变版本标签，并同时更新 `latest`：

```powershell
.\scripts\push-images-to-registry.ps1 -Tag 20260905-1200 -PushLatest
```

镜像内容一致不等于运行环境的所有状态自动复制。公司侧启动时还必须使用相同的 Compose/启动配置、环境变量、CPU 架构，以及对应的数据库和 MinIO 等挂载卷；脚本不会复制数据卷，也不会把账号密码写入镜像。部署端应使用上面列出的完整镜像地址和同一个版本标签。

## CI/部署侧镜像名

```text
10.25.13.206:5000/custom-develop/medical-report-mvp-backend:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-frontend:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-go-parser:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-slide-worker:<tag>
10.25.13.206:5000/custom-develop/medical-report-mvp-go-parser-vendor:<tag>
```

其中 vendor 镜像包含厂商 Parser 依赖；没有对应依赖或仓库不接收该镜像时，可使用 `-SkipVendor`。
