# 宝宝记录 Baby Record

面向新生儿家庭的自托管 H5/PWA，用于快速记录喂养、睡眠和排泄，并在家庭设备之间实时同步。

> 自托管运营者仍需自行完成隐私、安全和数据保留评审。

## 主要能力

- 母乳亲喂、母乳瓶喂、配方奶和泵奶分别记录与统计。
- 睡觉/醒来状态式操作，跨午夜睡眠按自然日切分。
- 便便属性、尿尿、补录、编辑、删除、历史和最近 7 天趋势。
- IndexedDB 本地优先；离线队列、创建幂等和多设备乐观锁冲突处理。
- HttpOnly 设备 Cookie，无账号密码；家庭邀请码用于家属加入。
- SSE 提交后通知其他家庭设备刷新。
- 可选的 DeepSeek 照护分析；默认关闭，不影响核心记录功能。

AI 输出只用于一般照护参考，不能替代医生诊断、处方或急救建议。

## 快速开始

### Docker Compose

需要 Docker Engine 和 Docker Compose。

```bash
cp .env.example .env
```

编辑 `.env`，至少设置不同的强密码：

```dotenv
DB_ROOT_PASSWORD=your-strong-root-password
DB_PASSWORD=your-strong-app-password
```

然后启动：

```bash
docker compose up -d --build
```

默认只监听回环地址，打开 `http://localhost:8088`。首次进入页面后创建家庭和宝宝；数据库不会预置用户、家庭、宝宝或邀请码。只有明确需要局域网直连并已评估访问边界时，才把 `WEB_BIND_ADDRESS` 改为具体网卡地址或 `0.0.0.0`。

需要让本机 Vite 或 E2E 直接访问后端时，叠加仅监听回环地址的开发配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

`docker compose down -v` 会删除数据库卷，只能用于明确可销毁的开发环境。

### 启用 AI

AI 默认关闭。启用前在 `.env` 中设置：

```dotenv
AI_ENABLED=true
DEEPSEEK_API_KEY=your-api-key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-v4-flash
```

服务端未启用或未配置密钥时，前端不会展示 AI 入口。提供商地址必须使用 HTTPS；只有回环测试地址允许 HTTP。宝宝姓名和内部 ID 不会进入 AI 快照，但部署者仍须向使用者说明第三方处理、保存期限和退出方式。

## 本地开发

要求：

- Node.js `20.19+` 或 `22.12+`，以及 npm。
- JDK 21；后端源码不支持 Java 17。
- Docker Compose，或自行准备兼容 MySQL 5.7 语法的 MySQL/MariaDB。Compose 默认使用 MariaDB 11.4 LTS。

安装并运行前端：

```powershell
npm --prefix .\web ci
npm --prefix .\web run dev
```

前端地址为 `http://127.0.0.1:5173`。启动本地后端前，设置数据库、Cookie 和 CORS 环境变量，然后运行：

```powershell
.\server\mvnw.cmd -f .\server\pom.xml spring-boot:run
```

完整变量及默认值见 [.env.example](.env.example)。

## 验证

```powershell
npm --prefix .\web test
npm --prefix .\web run build
.\server\mvnw.cmd -f .\server\pom.xml test
$env:DB_ROOT_PASSWORD='compose-check'; $env:DB_PASSWORD='compose-check'
docker compose --env-file .env.example config --quiet
```

`e2e-local.ps1` 会创建并删除隔离 fixture，默认拒绝非回环 API 或数据库。不要对真实家庭数据库使用 `-AllowNonLocalTarget`。

## 架构

```text
React/PWA → Dexie pending queue → Spring Boot API → MySQL/MariaDB
     ↑                 SSE changed after commit             │
     └───────────────────────────────────────────────────────┘
```

- `web/`：React 19、TypeScript、Vite、TanStack Query、Zustand、Dexie。
- `server/`：Java 21、Spring Boot 4、MyBatis、SSE、可选 AI 提供商。
- `sql/`：空库 schema 和按文件名执行的可重复迁移。
- `deploy/examples/`：不含真实域名或凭据的生产配置参考。

## 文档

- [系统需求](docs/product/system-requirements.md)
- [业务规则](docs/product/business-rules.md)
- [核心流程](docs/product/workflows.md)
- [API 概览](docs/api.md)
- [部署示例](deploy/README.md)
- [隐私与数据边界](PRIVACY.md)
- [安全策略](SECURITY.md)

## 隐私边界

本仓库按自托管软件发布：项目代码本身不会把部署实例的数据发送给仓库维护者。每个实例的运营者负责数据库、日志、备份、AI 提供商和当地合规。

当前版本没有完整家庭数据导出和一键删除能力，因此不应在没有额外隐私流程的情况下直接作为面向公众的托管服务。详见 [PRIVACY.md](PRIVACY.md)。

## 参与贡献

欢迎提交问题和改进。开始前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)；安全问题不要公开提交 Issue，请按 [SECURITY.md](SECURITY.md) 私下报告。

产品行为变更必须同步更新系统需求、业务规则、核心流程和相关测试。

## 第三方软件

依赖及特殊许可提示见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。精确前端版本由 `web/package-lock.json` 锁定，后端版本由 `server/pom.xml` 的固定 Spring Boot BOM 管理。

## License

本项目采用 [Apache License 2.0](LICENSE)。
