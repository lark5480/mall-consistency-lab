# 贡献指南

感谢关注 mall-consistency-lab。本仓库的核心命题是「不用 Seata / MQ，手写证明最终一致」，因此**一致性机制相关的改动有较高的评审门槛**。请先读完本页再提 PR。

> AI 编码 Agent（Claude Code / Cursor / Codex 等）的行为约定见 [AGENTS.md](AGENTS.md)，本页面向人类贡献者，两者共享同一套硬约束与文档同步要求。

## 环境要求

| 软件 | 最低版本 | 验证命令 |
|------|---------|---------|
| JDK | 21 | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Docker | 最新版（集成测试与 compose 必需；无 Docker 时 Testcontainers 集成层自动 skip） | `docker compose version` |
| Node.js | 20 | `node -v` |
| pnpm | 9 | `pnpm -v` |

## 开发流程

1. Fork 并克隆仓库，建议为每个改动单独开分支。
2. 全栈起法：`make build && make up`；后端裸跑必须显式提供 `JWT_SECRET`（缺失时服务启动即失败，设计如此）：
   `export JWT_SECRET=local-demo-jwt-secret-change-me-1234567890`
3. 前端在 `mall-consistency-lab-frontend/` 下执行 `pnpm install`，单包开发用 `pnpm --filter @mall/vue3-admin dev` / `pnpm --filter @mall/vue3-mall dev`。
4. 涉及库存、订单、状态流转、定时任务的改动，先读 [docs/CONSISTENCY.md](docs/CONSISTENCY.md)；技术选型拿不准先查 [docs/DECISIONS.md](docs/DECISIONS.md)。
5. 模块职责、端口、启动顺序见 [docs/project-structure.md](docs/project-structure.md)，完整文档索引见 [docs/README.md](docs/README.md)。

## 代码风格约定

- **后端**：Java 21，包名 `com.mall.<module>`；统一返回 `Result`，业务异常走公共异常码；Controller 只做参数与鉴权，逻辑在 Service；跨服务调用集中在 `feign/` 包。
- **前端**：Vue 3 `<script setup>` + TypeScript；共享类型放 `shared/`，不要在 B/C 两端各自定义重复 DTO；包级操作一律用 `pnpm --filter @mall/<pkg>`。
- **定时任务参数**：一律做成配置项 + compose 环境变量透传（便于调小周期做演示），不要硬编码。
- **注释、文档、提交信息统一用中文**；提交信息说明改动动机与影响面，而非仅罗列文件。

## 一致性红线（评审必查）

硬约束的**权威清单**在 [AGENTS.md §4](AGENTS.md#4-硬约束违反即破坏一致性评审必打回)，人类贡献者同样适用。要点提示：

- 订单状态写路径必须收敛到 `OrderService.transition()` 的 CAS，失败方不得执行任何库存动作；
- 取消订单顺序是「先 CAS 落 CANCELLED，再远程回补库存」，不得反转；
- Feign 调用必须在 DB 事务之外；扣库存接口不盲目重试；
- 幂等闸门是 `stock_dedup_log` 的 DB 唯一键，不是 Redis；
- 对账任务只补「确定该补」的，状态不明一律跳过。

不要凭常识"优化"掉幂等、CAS、短事务等看似冗余的保护——它们各自对应一个已修复的 P0 bug（见 [docs/CODE_REVIEW.md](docs/CODE_REVIEW.md)）。

已知且被接受的简化（无防重 token、无 MQ、定时任务单实例假设、模拟支付等）请**不要**顺手"修复"，理由见 [docs/CONSISTENCY.md](docs/CONSISTENCY.md) 第 5 节。

## PR 提交前自检清单

- [ ] `mvn -B test` 全绿（有 Docker 时集成层会真实跑：并发扣减只扣一次、并发回补幂等、对账孤儿回补、事务提交后缓存失效）。
- [ ] 前端改动：`pnpm -r exec vue-tsc --noEmit` + `pnpm -r build` 通过。
- [ ] 动了 HTTP 接口 → 同步更新 `docs/api-contracts/` 对应 OpenAPI 文件。
- [ ] 动了表结构 / 加索引 → **SQL 双写**：`docs/sql/*-schema.sql`（仅首次初始化生效）+ 可重复执行的 `docs/sql/zz-migration-*.sql`（存量环境用）。
- [ ] 动了一致性机制或新增定时任务 → 更新 `docs/CONSISTENCY.md`（含配置键汇总表）与 `docs/DECISIONS.md`。
- [ ] 新增/修改的行为若影响面上说明 → 同步 `README.md` 的「简化边界」「测试矩阵」等表；测试用例数量变化时一并更新 README 与 AGENTS.md 中的对应数字。
- [ ] 修改了启动命令 / 环境变量 / 演示账号 → 同步更新 `README.md`「快速启动」与 [AGENTS.md](AGENTS.md) §3。
- [ ] 未提交任何真实密钥（`.env` 已在 `.gitignore` 中，本地配置参考 `.env.example`；仓库内的 `JWT_SECRET`/演示账号均为本地 demo 值）。
- [ ] 未夹带无关的格式化 / 重构 / 版本升级改动。

## 版本冻结策略

Spring Cloud Alibaba / Spring Cloud / Spring Boot / Nacos 是强绑定的版本组合，矩阵的**权威真源**是 [AGENTS.md §4.8](AGENTS.md#4-硬约束违反即破坏一致性评审必打回)（AI 与人类贡献者同守这一份）。

- **请勿在改动中顺手升级框架 / 中间件版本**，修改 `pom.xml` 或 `docker-compose.yml` 时不要顺带 bump 版本号。
- 解冻升级需由维护者在明确场景下统一推进（升级时同步 §4.8 矩阵与 README 技术栈表）。

## 提问与反馈

Issue 中请附上复现步骤、完整报错与相关日志（`make logs`），涉及并发/一致性问题的请注明测试用例编号或服务模块。
