# 前端与离线同步

适用于 `web/src/` 的状态、组件、离线队列和实时刷新改动。

## 组件边界

- `App.tsx` 保持类组件，使用 `React.Component` 与 `this.setState`；不要在其中引入 hooks。
- `AiWorkspace.tsx` 和新函数组件可以使用 hooks，但副作用必须有清理逻辑和过期结果守卫。
- Sheet 复用 `BottomSheet` / `SheetHeader`；公共交互必须保留可访问名称、按钮类型和关闭路径。
- UI 组件只接收所需 props；日期、合并、统计和表单校验优先落到 `domain/` 纯函数。

## 身份与本地作用域

- 每个依赖家庭或宝宝身份的异步方法都先捕获 identity，并在每次 `await` 后用 `isCurrentIdentity()` 拒绝过期结果。
- 切换家庭或登出必须同步递增 `identityEpoch`、停止 SSE/重试，并清空家庭专属视图状态。
- Dexie 作用域包含部署键、`familyId` 和 `babyId`；不同后端或家庭之间不得共享缓存。
- `localStorage` 只用于旧版首次迁移和 IndexedDB 不可用时的兼容镜像。

## 待同步队列不变量

- 创建使用客户端 UUID `clientEventId` 保证幂等；服务端重复创建只返回原记录 ID，不覆盖内容。
- 修改/删除携带最初的 `expectedUpdatedAt`；`409` 时拉取权威数据，不覆盖其他设备的更新。
- 同一记录的连续修改通过现有 `enqueue()` / `reconcilePendingSuccess()` 合并；不要另写一套队列状态机。
- 请求飞行中发生的新编辑或删除必须保留为后继 action，不能被旧响应清掉。
- 终态 4xx action 标记 `blocked` 并隔离；可重试状态继续使用现有退避机制。

## 实时与配置

- SSE `changed` 事件只负责让 Query 缓存失效并刷新 Dashboard；HTTP 仍是写入通道。
- 前台恢复、重新联网和 SSE 重连都必须补一次权威数据对账。
- 只保留 `web/vite.config.ts`；不要提交编译生成的 `vite.config.js` 或 `.d.ts`。
- Vite 7 本地建议 Node `20.19+` 或 `22.12+`；Docker Web 使用 Node 22。

