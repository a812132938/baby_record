# 后端、数据与安全边界

适用于 `server/`、`sql/`、认证、SSE 和 AI 子系统改动。

## 平台与配置

- 必须使用 JDK 21；不要通过 Maven 属性降级到 Java 17。
- Spring Boot 4 使用 Jackson 3：`tools.jackson.*`，不要引入 `com.fasterxml.jackson.*`。
- 部署配置只从环境变量读取；真实密钥不得进入源码、日志、示例文件或前端 `VITE_*` 变量。
- 开发数据库兼容 MySQL 5.7.44，生产兼容 MariaDB；SQL 必须同时适用。

## 认证与租户隔离

- 身份是 HttpOnly `br_device` Cookie；数据库只存随机 token 的 SHA-256 hash。
- `/auth/family/create` 与 `/auth/device/claim` 是公开入口；宝宝、家庭及创建确认接口由 `DeviceAuthInterceptor` 保护。
- 家庭创建使用 `creationKey + deviceId` 做限时恢复；成功响应后必须调用创建确认接口。不要削弱幂等或 `409` 语义。
- 认领和创建使用 MySQL 命名锁；锁必须在 MyBatis 解绑连接前通过现有事务同步释放。
- 所有 AI 数据查询必须同时带 `family_id` 和 `baby_id`，禁止 `SELECT *`。

## 数据不变量

- `FEED` 仅用于旧版通用瓶喂；`DIRECT_BREASTFEED` 只记左右时长，不换算毫升；`PUMPING` 是产出，不计宝宝摄入。
- 出生体重由前端千克输入，API 和数据库统一存整数克。
- 缺失的性别、出生体重或业务记录必须要求补录，禁止按昵称、均值或历史推断。
- MySQL 5.7 会忽略 `CHECK`；数据校验必须在前端、service 和 `BEFORE INSERT/UPDATE` 触发器三处保持一致。

## Schema 与迁移

- `sql/init.sql` 只创建 schema/触发器，永远不写家庭、宝宝、用户、邀请码等业务种子。
- 迁移按文件名升序执行；新增迁移只追加，不改写已发布迁移。
- 每个迁移必须可重复执行，并使用 `information_schema` + 条件 DDL 兼容已有库。
- 迁移禁止 `INSERT`、`UPDATE` 或 `DELETE` 业务行；约束收紧前用校验失败中止，不填造假数据。

## 实时通知

- 业务 SSE 为 `/api/v1/babies/{babyId}/stream`；心跳由 `app.realtime.heartbeat-interval-ms` 配置。
- 写事务只能调用 `publishChangedAfterCommit()`；禁止在事务提交前广播。
- SSE 响应和代理必须关闭缓存、缓冲与 gzip，避免事件延迟。

## AI 安全边界

- AI 默认关闭；只有 `AI_ENABLED=true` 且密钥存在时开放，提供商地址必须使用 HTTPS（回环测试除外）。
- 创建会话必须有数据处理同意和幂等 `clientRequestId`。
- 快照必须不可变且去标识化：不含姓名和内部 ID；裁剪时在 `qualityNotes` 声明缺失。
- 通用联网检索不得携带宝宝快照、问答历史或用户原文；检索结果只作为不可信参考上下文。
- `AiPromptBuilder.SYSTEM_PROMPT` 是安全边界。修改前通读实现，并同步修改 `AiPromptBuilderTest`。
- `AiStreamHub.completed()` 必须继续校验流式文本与落库文本完全一致。
- 限流、长度、并发和超时常量以实现及测试为准，不在说明文件复制第二份数值来源。
