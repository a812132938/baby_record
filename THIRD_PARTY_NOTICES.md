# Third-party notices

本项目依赖第三方开源软件。精确前端版本见 `web/package-lock.json`，后端版本见 `server/pom.xml` 及其固定的 Spring Boot 依赖管理。

主要直接依赖：

| 组件 | 许可证 |
|---|---|
| React / React DOM | MIT |
| TanStack Query | MIT |
| Dexie | Apache-2.0 |
| Motion | MIT |
| Zustand | MIT |
| Vite / Vite PWA | MIT |
| TypeScript | Apache-2.0 |
| Spring Boot | Apache-2.0 |
| MyBatis | Apache-2.0 |
| MySQL Connector/J | GPL-2.0 with Universal FOSS Exception |
| MariaDB Connector/J | LGPL-2.1-or-later |

前端传递依赖 `caniuse-lite` 包含按 CC-BY-4.0 发布的浏览器兼容性数据。再分发者应保留其包内许可证与署名信息。

本文件是醒目标记，不替代各依赖包随附的完整许可证文本。发布容器、安装包或其他二进制产物前，应基于实际锁定版本生成并归档完整 SBOM 与许可证报告。
