# 部署约束

适用于 Docker、Nginx、systemd 和数据库升级。公开源码只维护可复用的自托管路径，不记录任何维护者真实基础设施。

## 环境边界

- Docker Compose 是公开支持的部署入口；`.env.example` 中密码故意留空，未设置时必须拒绝启动。
- `docker-compose.dev.yml` 仅将后端暴露到宿主回环地址，生产不得叠加。
- `docker compose down -v` 会删除数据库卷，除非任务明确要求，否则不要执行。
- 后端生产只监听回环或容器内部网络；公网统一经受控反向代理进入。
- 继续保持 MySQL 5.7 SQL 兼容测试，但新的生产示例优先使用仍受维护的 MariaDB LTS。

## 公开与私有边界

- `deploy/examples/` 只放 `example.com` 等通用模板；真实域名、证书路径、服务器 IP、主机清单、环境文件、备份和发布脚本留在私有运维仓库。
- 示例不得包含证书私钥、API Token、数据库凭据或维护者本机绝对路径。
- 修改部署模板时检查 README、契约测试和 `.gitignore`，避免私有配置重新进入公开文件列表。

## Nginx/PWA

- 匿名建家庭、设备认领、AI 写入和 AI 流分别限流。
- 所有 SSE 路径关闭缓存、`proxy_buffering` 和 gzip，并设置足够的读取超时。
- `sw.js`、`registerSW.js`、`index.html` 和 `manifest.webmanifest` 必须 no-store，包括 CDN 缓存头。
- 使用 CDN 或负载均衡器时，只信任运营方明确维护的代理地址；不要照搬过期的公网 IP 列表。

## 数据库升级

- `sql/init.sql` 只用于空库初始化；已有数据库按迁移文件名升序执行。
- 迁移前备份，迁移后核对业务表行数和目标 schema；失败时停止发布并恢复到可验证状态。
- 公开文档不承诺维护者私有的发布流水线，部署者需要为自己的平台建立原子切换、健康检查和回滚。
