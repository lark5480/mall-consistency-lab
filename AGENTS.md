# AGENTS.md

> 面向 AI Agent（Codex / Claude Code / Cursor / Copilot 等）的仓库上手手册。
> 本文件只回答「**在这个仓库里怎么正确地改代码**」。需求背景与设计的**理由**一律查 `docs/`，此处不复制。

## 0. 三十秒定位

- **项目类型**：Java 21 + Spring Cloud 微服务电商后端 + Vue 3 前端（pnpm workspace，B 端 / C 端两个包）。
- **唯一核心命题**：不使用 Seata / MQ，手写实现「下单远程扣库存 → 本地落单 → 失败补偿 → 定时对账」的 Saga 闭环，并用并发测试证明最终一致。
- **改动前的默认动作**：涉及库存、订单、状态流转、定时任务的代码，先读 [`docs/CONSISTENCY.md`](docs/CONSISTENCY.md)；拿不准该不该做某个技术选型，先查 [`docs/DECISIONS.md`](docs/DECISIONS.md)（40+ 条带备选方案的决策记录，几乎覆盖了你会想到的所有"为什么不…"）。

## 1. 文档路由表（按任务查，不要全读）

| 你的任务 | 读这一份 |
|---|---|
| 改下单 / 扣库存 / 补偿 / 对账 / 订单状态 | [`docs/CONSISTENCY.md`](docs/CONSISTENCY.md)（六道防线逐条对应到具体文件与行） |
| 想引入某个框架、改架构、问"为什么不用 X" | [`docs/DECISIONS.md`](docs/DECISIONS.md) → [`docs/ECOSYSTEM.md`](docs/ECOSYSTEM.md) |
| 改 / 加接口 | [`docs/api-contracts/`](docs/api-contracts/README.md)（OpenAPI 3.0，5 份） |
| 改表结构 / 加索引 | [`docs/sql/`](docs/sql/)（三库初始化 + 幂等迁移脚本） |
| 找模块职责、端口、启动顺序 | [`docs/project-structure.md`](docs/project-structure.md) |
| 功能范围与验收标准 | [`docs/PRD.md`](docs/PRD.md)、[`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) |
| 想知道评审过哪些坑 | [`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md) |

## 2. 仓库结构与端口

```text
mall-common                     Result / JWT（启动 fail-fast）/ 公共异常，被所有业务模块依赖
mall-gateway    :8080           Spring Cloud Gateway：JWT 鉴权、白名单、CORS、剥离伪造 X-User-Id
mall-user       :8081           注册/登录/用户资料/地址簿        → MySQL mall_user
mall-product    :8082           商品 CRUD、详情缓存、幂等扣库存   → MySQL mall_product + Redis
mall-order      :8083           订单 Saga 编排 + 状态机          → MySQL mall_order
mall-consistency-lab-frontend   pnpm workspace：packages/vue3-admin(:3000) + packages/vue3-mall(:3001) + shared 共享类型
docs/                           见第 1 节路由表
scripts/chaos-test.sh           混沌测试（随机 kill order-service 验证对账兜底）
```

三库分库（`mall_user` / `mall_product` / `mall_order`），**跨库不做 join**，需要跨服务数据走 Feign 或快照字段。

## 3. 环境与常用命令

前置：JDK 21、Maven、Docker（集成测试 / compose 必需）、Node 20 + pnpm 9（前端）。

```bash
# 后端
mvn -B compile                 # 最快编译校验
mvn -B test                    # 全部测试；本机无 Docker 时 Testcontainers 集成层自动 skip
mvn -B package -DskipTests     # 打包（make build）

# 全栈
make build && make up          # 打包 + docker compose up -d --build
make logs                      # 看日志
make chaos                     # 混沌测试（需完整栈已 up）

# 前端（均在 mall-consistency-lab-frontend/ 下执行）
pnpm install
pnpm -r build                  # 构建全部包（CI 同款）
pnpm -r exec vue-tsc --noEmit  # 类型检查
pnpm --filter @mall/vue3-admin dev
pnpm --filter @mall/vue3-mall  dev
```

- **后端裸跑（非 compose）必须显式提供 `JWT_SECRET`**，缺失时服务启动即失败（设计如此，无内置默认密钥）：
  `export JWT_SECRET=local-demo-jwt-secret-change-me-1234567890`
- 宿主机 6379 被占用：`REDIS_HOST_PORT=16379 make up`（PowerShell 用 `$env:REDIS_HOST_PORT="16379"`）。
- 演示管理员 `admin / admin123`（仅本地演示种子数据）。
- 旧数据卷需执行一次 `docs/sql/zz-migration-v1.4.sql`（可重复执行）。

## 4. 硬约束（违反即破坏一致性，评审必打回）

1. **CAS 失败方不得执行任何库存动作。** 手动流转 pay / cancel / ship / complete 四个入口全部收敛到 `OrderService.transition()`，条件更新影响行数 ≠ 1 即抛 `ORDER_STATE_ERROR`（40002 / HTTP 409）；超时自动关单 `autoCloseExpiredPendingOrders()` 是遵守同一纪律的独立行级 CAS（`status='PENDING'` 才置 CANCELLED，输家跳过、不触库存），与 CONSISTENCY.md ⑥ 的口径一致。禁止新增绕过 CAS 的订单状态写路径。
2. **取消订单的顺序是：先 CAS 落 CANCELLED，再远程回补库存。** 反过来会产生"已支付订单 + 凭空多出的库存"（v1.4 P0 bug 的直接教训）。
3. **Feign 调用必须在 DB 事务之外。** 下单落库用 `TransactionTemplate` 编程式短事务包裹，不得用 `@Transactional` 把远程调用圈进事务。
4. **扣库存接口不盲目重试。** 超时/结果不明时先查 `stockStatus(orderNo)` 再决策；重试会超卖。
5. **幂等闸门是 DB 唯一键，不是 Redis。** 所有扣减/回补必须先过 `stock_dedup_log` 的条件更新（`DEDUCTED→RESTORED` 抢占回补权），回补数量取落库记录值而非入参。
6. **对账任务只补"确定该补"的**：仅当订单不存在（孤儿）或已 CANCELLED 才回补；状态不明一律跳过，绝不误补。
7. **网关安全**：统一剥离外部传入的 `X-User-Id` 再注入鉴权后的可信值；转发前移除 `Authorization`；`/internal/**` 不经网关路由；`/api/v1/admin/**` 与商品写接口强制 ADMIN。
8. **版本矩阵不要单独升级**：Spring Cloud Alibaba 2025.0.0.0 ↔ Spring Cloud 2025.0.x ↔ Spring Boot 3.5.x ↔ nacos-server v3.0.3（nacos-client 3.0.3）。MyBatis-Plus 3.5.9+ 的分页拦截器在 `mybatis-plus-jsqlparser`，需与 starter 成对引入。
9. **SQL 变更要双写**：`docs/sql/*-schema.sql` 只作用于 MySQL 首次初始化（`docker-entrypoint-initdb.d`），因此存量环境的任何表结构/索引变更必须另外提供可重复执行的 `zz-migration-*.sql` 脚本。

## 5. 改完代码的自检清单

1. `mvn -B test` 全绿（有 Docker 时集成层会真实跑：同单号 16 线程并发扣减只扣一次、8 线程并发回补幂等、对账孤儿回补、事务提交后缓存失效）。
2. 前端改动：`pnpm -r exec vue-tsc --noEmit` + `pnpm -r build`。
3. 动了 HTTP 接口 → 同步更新 `docs/api-contracts/` 对应 OpenAPI 文件。
4. 动了一致性机制或新增定时任务 → 更新 `docs/CONSISTENCY.md`（含配置键汇总表）与 `docs/DECISIONS.md`。
5. 新增/修改的行为若影响面上说明 → 同步 `README.md` 的「简化边界」「测试矩阵」等表。
6. 测试矩阵数字（当前：单测 62 例 / 集成 4 例）变化时一并更新 README。
7. 修改了启动命令 / 环境变量 / 演示账号 → 同步更新 `README.md` 的「快速启动」与本文 §3；两处内容冲突时以 `README.md` 为准并回改本文。

## 6. 协作约定

- 注释、文档、提交信息统一用中文。
- **未经用户明确要求，不要执行 `git commit` / `git push`**；改动完成后先列出改了哪些文件、为什么改、验证结果，等用户确认。
- 不要凭常识"优化"掉幂等、CAS、短事务等看似冗余的保护——它们各自对应一个已修复的 P0 bug（见 `docs/CODE_REVIEW.md`）。

## 7. 代码风格约定

- 后端：Java 21，包名 `com.mall.<module>`；统一返回 `Result`，业务异常走公共异常码；Controller 只做参数与鉴权，逻辑在 Service；跨服务调用集中在 `feign/` 包。
- 前端：Vue 3.4 `<script setup>` + TypeScript；共享类型放 `shared/`，**不要**在两端各自定义重复 DTO；包级操作一律用 `pnpm --filter @mall/<pkg>`。
- 定时任务相关参数一律做成配置项 + compose 环境变量透传（便于调小周期做演示），不要硬编码。

## 8. 已知边界（不要当成 bug 去"修"）

以下是**已知且被接受的**简化。**除非用户明确要求，不要主动"修复"或引入对应设施**（ShedLock / 去重 token / MQ 等）；理由与演进条件见 `docs/CONSISTENCY.md` 第 5 节。

- 创建订单无客户端防重 token（双击可能生成两笔订单，属体验问题，非数据错误）。
- 无 MQ / outbox：补偿依赖同步调用 + 对账兜底。
- 定时任务按单实例部署假设，未加分布式锁（重复执行不破坏正确性；多副本前需引入 ShedLock）。
- 支付为模拟支付，不接任何真实支付渠道；发货仅流转状态，不填物流单号。

## 9. 典型任务最短路径

- **加一个订单状态流转** → `OrderService.transition()` / `assertTransition()` 加规则 → 补单测 → 更新 `docs/CONSISTENCY.md` 状态机图 → 更新 OpenAPI。
- **给商品加字段** → `Product` 实体 + `mall_product` 表 → `docs/sql/` 迁移脚本 + schema → 商品缓存失效逻辑（若字段进详情缓存）→ 前端 `shared/` 类型与两个包的表单。
- **加一个定时任务** → 参考 `OrphanDeductionReconcileJob` / `OrderAutoCloseTask`（参数可配、批扫、逐条 CAS、失败留痕由对账兜底）→ `docs/CONSISTENCY.md` 配置表加行 → compose 透传环境变量。
