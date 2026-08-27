# Security Policy

## Supported versions

安全修复只针对默认分支的最新代码。首次正式发布后，本节应改为明确的受支持版本表。

## Reporting a vulnerability

请使用 GitHub 的 **Private vulnerability reporting** 私下提交安全问题，不要创建公开 Issue，也不要附带真实宝宝记录、邀请码、Cookie、API Key、数据库备份或服务器地址。

报告中请包含：

- 受影响版本或提交。
- 最小复现步骤和预期影响。
- 已采取的临时缓解措施。
- 不含真实个人数据的验证材料。

维护者确认问题并准备修复前，请勿公开利用细节。若仓库尚未启用私密漏洞报告，首次公开前必须先在 GitHub Security 设置中启用。

## Deployment responsibility

自托管运营者负责 TLS、反向代理信任边界、数据库访问、备份加密、日志脱敏、密钥轮换、依赖更新和安全事件响应。`deploy/examples/` 仅为参考，不能替代目标环境安全评审。

