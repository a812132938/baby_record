# Baby Record 开发指引

Baby Record 是面向新生儿家庭的 H5/PWA：前端负责本地优先记录与同步，后端负责设备认证、家庭数据、SSE 和 AI 照护分析。

## 全局硬约束

- 保留用户现有改动；删除或整文件重写前先核对精确目标，禁止批量清理。
- 后端必须使用 JDK 21；开发兼容 MySQL 5.7.44，生产兼容 MariaDB。
- 配置和密钥只来自环境变量；`DEEPSEEK_API_KEY` 不得进入源码、日志或任何 `VITE_*` 变量。
- `sql/init.sql` 只建 schema；迁移可重复执行且不修改业务行。
- `.omx/state/`、`server/target/`、`web/dist/` 和 `*.tsbuildinfo` 是产物，不得作为源码参考。
- 真实域名、证书、服务器清单、环境文件、备份和运维脚本不得进入公开仓库；`deploy/examples/` 只放通用模板。
- 系统行为变更必须同步更新对应需求、业务规则、核心流程和测试，不能只改实现。
- 修改前只阅读与任务相关的下列主题文件；不要默认加载全部文档。

## 常用命令

```powershell
npm --prefix .\web test
npm --prefix .\web run build
.\server\mvnw.cmd -f .\server\pom.xml test
$env:DB_ROOT_PASSWORD='compose-check'; $env:DB_PASSWORD='compose-check'
docker compose --env-file .env.example config --quiet
```

Maven 必须运行于 Java 21。项目没有独立 linter 或 formatter；GitHub Actions 是公开仓库门禁，日常仍按改动范围选择最小充分验证。

## 主题指引

- [系统需求](docs/product/system-requirements.md)：角色、功能范围和质量要求，作为功能验收基准。
- [业务规则](docs/product/business-rules.md)：权限、校验、数据语义和一致性规则。
- [核心流程](docs/product/workflows.md)：跨前后端主路径、状态变化和异常闭环。
- [架构与代码归属](docs/agent-instructions/architecture.md)：目录边界和新模块放置。
- [前端与离线同步](docs/agent-instructions/frontend.md)：类组件、身份守卫、Dexie、pending queue、SSE。
- [后端、数据与安全](docs/agent-instructions/backend-data-security.md)：认证、租户隔离、迁移、实时通知、AI 安全。
- [验证策略](docs/agent-instructions/testing.md)：按改动选测试、契约测试、浏览器与端到端验证。
- [部署约束](docs/agent-instructions/deployment.md)：Docker、生产发布、回滚和 Nginx/PWA 不变量。
