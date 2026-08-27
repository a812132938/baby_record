# 架构与代码归属

适用于定位代码、增加模块或调整边界。具体数据一致性和安全规则见对应主题文件。

## 系统边界

| 层 | 技术 | 职责 |
|---|---|---|
| `web/` | React 19、TypeScript、Vite、Dexie、TanStack Query、Zustand | H5/PWA、离线记录、同步编排 |
| `server/` | Java 21、Spring Boot 4、MyBatis、SSE | 认证、业务规则、持久化、实时通知、AI 会话 |
| `sql/` | MySQL 5.7 / MariaDB 兼容 SQL | 初始化 schema、可重复迁移、数据库校验 |
| `deploy/` | Nginx、systemd、Shell | 原子发布、回滚、生产运行配置 |

## 源码地图

| 路径 | 放置内容 |
|---|---|
| `web/src/App.tsx` | 身份、同步、事件写入和页面状态编排 |
| `web/src/domain/` | 无 UI 副作用的模型、日期、事件合并和统计函数 |
| `web/src/components/` | 可跨功能复用的展示组件 |
| `web/src/features/` | 首页和按业务组织的界面；Sheet 放 `features/sheets/` |
| `web/src/data/` | Dexie、本地作用域、待同步队列和设备存储 |
| `web/src/api/`、`stores/` | HTTP/Query 接口与轻量运行状态 |
| `web/src/ai/` | AI 工作台、流式协议和 AI 前端类型 |
| `server/.../controller/` | HTTP 契约、认证入口和参数校验 |
| `server/.../service/` | 事务、业务规则和跨存储编排 |
| `server/.../mapper/` | 带租户作用域的注解 SQL |
| `server/.../realtime/` | 业务 SSE 与 AI 流式 SSE |
| `server/.../auth/`、`config/` | 设备身份、拦截器、线程池和客户端配置 |

## 扩展规则

- 新 UI 优先作为功能组件接收数据和回调；不要把展示 JSX 再堆回 `App.tsx`。
- 可测试的计算、校验和队列合并放纯函数模块，不依赖 React 或浏览器全局状态。
- 后端保持 `controller → service → mapper` 边界；事务和跨资源一致性归 service。
- 新事件类型或字段必须同时检查前端模型、服务校验、Mapper SQL、数据库触发器/迁移和测试契约。
- 不建立第二套入口、配置或数据模型；复用现有模块后再考虑新抽象。
- `.omx/state/`、`server/target/`、`web/dist/` 和 `*.tsbuildinfo` 都是产物，不是参考实现。

