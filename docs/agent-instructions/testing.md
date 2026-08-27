# 验证策略

适用于选择测试范围、执行本地验证和解释失败。

## 按改动选择证据

| 改动 | 最小验证 |
|---|---|
| 前端纯函数、队列或组件 | `npm --prefix .\web test` |
| TypeScript 接口、入口或构建配置 | `npm --prefix .\web run build` |
| Java 业务、Mapper、认证或配置 | JDK 21 下运行相关测试；跨模块改动运行完整 Maven 测试 |
| SQL/迁移 | 对应契约测试，并在适用的 MySQL/MariaDB 环境验证可重复执行 |
| Compose 配置 | 临时设置两个检查密码后运行 `docker compose --env-file .env.example config --quiet` |
| 用户可点击的 UI 流程 | 浏览器真实交互、控制台检查及相关视口验证 |

项目没有单独的 linter 或 formatter；`.github/workflows/ci.yml` 是公开仓库的合并门禁。不要用重复的全量测试代替有针对性的验证。

## 命令

```powershell
npm --prefix .\web test
npm --prefix .\web run build
.\server\mvnw.cmd -f .\server\pom.xml test
$env:DB_ROOT_PASSWORD='compose-check'; $env:DB_PASSWORD='compose-check'
docker compose --env-file .env.example config --quiet
```

- Maven 必须先用 `mvn -version` 确认运行于 Java 21；PowerShell 中的 Maven `-D...` 参数要加引号。
- `web/tests/dynamic-config.test.mjs` 混合源码契约与可执行纯函数测试；`domain-behavior.test.mjs` 通过 Vite SSR 执行领域行为。
- Java 的 `InitSqlContractTest`、`ApplicationConfigContractTest`、`AiMapperScopeContractTest` 等会断言源码文本；重命名或移动契约片段时同步更新断言。

## 浏览器验证

- 改动首次创建、邀请、记录 Sheet、历史、统计、资料、设备或 AI 会话时，使用当前可用的浏览器控制能力真实点击；不要用 curl 代替 UI 验证。
- 同时检查页面可见状态、控制台错误和必要的网络请求。
- 响应式基线为 `390×844` 与 `1440×900`；只在布局或相关交互变化时运行两档视口。

## 本地端到端

`e2e-local.ps1` 通过公开 API 创建隔离家庭，并在结束时按随机设备 UUID 清理 fixture。脚本默认拒绝非回环 API 或数据库；`-AllowNonLocalTarget` 只允许用于明确隔离、可销毁的测试环境。仅在需要数据库/API 全链路证据时运行，`-TestFailPoint` 只供自动化故障验证。
