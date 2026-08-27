# Contributing

感谢参与 Baby Record。提交改动前，请先阅读 [AGENTS.md](AGENTS.md)，并按任务范围阅读其中链接的系统需求、业务规则和技术边界。

## 开发环境

- Node.js `20.19+` 或 `22.12+`。
- JDK 21。
- Docker Compose，或兼容 MySQL 5.7 语法的本地数据库。

```powershell
npm --prefix .\web ci
npm --prefix .\web test
npm --prefix .\web run build
.\server\mvnw.cmd -f .\server\pom.xml test
```

## 提交要求

- 保持改动聚焦，不提交构建产物、真实配置、个人数据或密钥。
- 不改变无关代码，不新增依赖，除非变更确有必要并说明许可与维护成本。
- 行为变化同步更新 `docs/product/` 中对应需求、规则、流程和相关测试。
- SQL 继续兼容 MySQL 5.7 与 MariaDB；新增迁移只追加且可重复执行。
- UI 改动提供手机和桌面视口的验证证据。
- 安全问题按 [SECURITY.md](SECURITY.md) 私下报告。

## Pull request checklist

- 说明问题、方案、影响范围和验证结果。
- 标出数据迁移、兼容性、隐私或安全影响。
- 确认没有提交 `.env`、证书、数据库、日志、生产域名配置或生成目录。
- 贡献内容必须由提交者合法授权，并可按项目的 Apache-2.0 许可证发布。
