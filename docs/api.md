# API 概览

默认前缀为 `/api/v1`。除家庭创建、设备认领、健康检查和能力查询外，业务接口使用 HttpOnly `br_device` Cookie 认证。

## 运行状态

```text
GET /health
GET /capabilities
```

`/capabilities` 当前返回 `aiEnabled`，供前端按服务端运行时配置展示可选能力。

## 设备与家庭

```text
POST /auth/family/create
POST /auth/family/create/confirm
POST /auth/device/claim
GET  /auth/me
POST /auth/logout

GET    /family/invite
GET    /family/devices
DELETE /family/devices/{deviceId}
```

创建家庭和认领设备是匿名入口，生产反向代理必须分别限流。家庭创建用 `creationKey + deviceId` 恢复丢失响应；重试返回首次创建结果，不能改写已提交资料。

## 宝宝资料与记录

```text
PATCH /babies/{babyId}

GET    /babies/{babyId}/dashboard
GET    /babies/{babyId}/stats?days=7
GET    /babies/{babyId}/events?date=YYYY-MM-DD
POST   /babies/{babyId}/events/feed
POST   /babies/{babyId}/events/feeding
POST   /babies/{babyId}/events/simple
PATCH  /babies/{babyId}/events/{eventId}
DELETE /babies/{babyId}/events/{eventId}?expectedUpdatedAt=...

POST /babies/{babyId}/sleep/start
POST /babies/{babyId}/sleep/end
POST /babies/{babyId}/sleep/{eventId}/end
```

创建事件使用 `clientEventId` 幂等；修改和删除使用 `expectedUpdatedAt` 乐观锁。所有宝宝接口同时校验当前设备的家庭和宝宝作用域。

## 实时同步

```text
GET /babies/{babyId}/stream
Accept: text/event-stream
```

写事务提交后发送 `changed`。客户端收到通知后重新读取权威数据，SSE 本身不承载完整业务记录。

## AI 会话

只有 `AI_ENABLED=true` 且服务端配置了非空密钥时才展示入口；提供商仍会在实际请求时校验密钥：

```text
GET    /babies/{babyId}/ai/conversations
POST   /babies/{babyId}/ai/conversations
GET    /babies/{babyId}/ai/conversations/{conversationId}
DELETE /babies/{babyId}/ai/conversations/{conversationId}
POST   /babies/{babyId}/ai/conversations/{conversationId}/messages
POST   /babies/{babyId}/ai/conversations/{conversationId}/retry
GET    /babies/{babyId}/ai/conversations/{conversationId}/snapshots/{snapshotId}
GET    /babies/{babyId}/ai/conversations/{conversationId}/messages/{messageId}/stream
```

创建会话必须显式提交数据处理同意和幂等请求 ID。AI 查询继续受家庭与宝宝双重作用域保护。
