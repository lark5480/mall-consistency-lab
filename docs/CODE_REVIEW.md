# Mall-Learning 项目 Code Review 报告（v2 · Agent Teams 复核版）

> **项目**：mall-learning（现名 mall-consistency-lab）— Spring Cloud 微服务电商学习项目
> **技术栈**：JDK 17、Spring Boot 3.2.4、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0、MyBatis-Plus 3.5.5、JWT 0.12.5、MySQL 8.0、Redis 7.2、Nacos 2.3.2
> **规模**：5 个后端 Maven 模块（mall-common、mall-gateway、mall-user、mall-product、mall-order），79 个 Java 文件（71 主代码 + 8 测试，共 48 个测试用例）；另含前端 pnpm workspace（vue3-admin / vue3-mall / shared）
> **评审方法（v2）**：Agent Teams 4 个并行评审 Agent 分别精读 mall-common+gateway、mall-user+product、mall-order、横切面（POM/Docker/SQL/CI/文档），主 Agent 逐条比对 v1 报告结论并对重大修正点亲自抽查源码验证后汇总成稿。（v1 由主 Agent 单独完成；本次 subagent 调用全部成功。）

---

## 0. 本次复核对 v1 报告的核对结论（先读这里）

v1 报告共 ~120 条观点，复核结果：**✅ 确认保留约 85 条**（行号与结论基本准确），**❌ 推翻修正 12 条**（结论错误或方向相反），**⚠️ 校准 15 条**（事实对但严重度/建议需修正），**🆕 新增 30+ 条** v1 遗漏的问题（其中 2 条为一致性真 bug）。

### 0.1 ❌ 推翻修正清单（v1 结论错误，本版已更正）

| # | v1 观点 | 复核结论 | 证据 |
|---|---|---|---|
| 1 | **1.5** cancel 方法标了 `@Transactional` 且内部调 Feign，长期持有 DB 连接，与 create 设计矛盾 | **不成立**。cancel **没有** `@Transactional`，注释明确写了"与 create() 保持一致的事务策略：先远程调用回补库存（不占 DB 连接），再用 TransactionTemplate 仅包裹本地状态更新"。pay/complete/ship 虽标 `@Transactional` 但事务内只有本地 CAS，无任何 Feign | `OrderService.java:97-128` |
| 2 | **1.5 / TOP10-P0** restoreStock 双 update 非原子 + `@Transactional` 因 Spring 自调用失效 | **不成立**。restoreStock 标了 `@Transactional` 且调用方仅 `InternalProductController` 与对账 Job，均为注入 Bean 的外部调用、走代理，事务生效；两个 update 同库同事务，原子；`Product` 带 `@Version`，并发双回补时第二个事务乐观锁冲突整体回滚。v1 的 P0 #3 应撤销 | `ProductService.java:147-170`、`Product.java:23-25`、`MybatisPlusConfig.java:16`（残留加固点见 2.4 节 🆕） |
| 3 | **1.5** 订单号 `currentTimeMillis` "并发下不唯一（多核同时调用）" | **基本不成立**。系统时钟全核一致；UUID4 前 8 位 hex = 完整 32bit 随机，同毫秒碰撞概率 2^-32/对；且 `uk_order_no` 唯一索引兜底，碰撞会抛 DuplicateKey 走补偿路径。真实弱点是碰撞善后路径重（用户看到 500 + 库存补偿往返），建议碰撞时原地重生成订单号重试，而非换算法 | `OrderService.java:85`、`order-schema.sql:18` |
| 4 | **3.1** 删分类前 `selectCount` 全表扫，建议维护计数字段 | **不成立**。`idx_product_category (category_id)` 索引存在，是索引计数扫描；计数字段属过度设计且引入一致性负担。现状正确 | `CategoryService.java:64-65`、`product-schema.sql:27` |
| 5 | **3.1** 订单分页 `orderByDesc("created_at")` 无索引，深分页慢 | **C 端不成立**。`idx_order_user_created (user_id, created_at)` 完整覆盖 C 端列表。真实缺口在别处：adminList 的 status 过滤、autoComplete 的 (status, updated_at)、统计接口的 created_at 范围均无索引 | `OrderService.java:136,197,156-158,206-219`、`order-schema.sql:19` |
| 6 | **4.8** MySQL 默认 latin1，建议 utf8mb4 | **不成立**。三份 schema 均显式 `DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` + 每表 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`；URL 的 `characterEncoding=utf8` 在 Connector/J 8 中即映射 utf8mb4，无害。字符集处理是本项目强项，v1 把 URL 参数误读为库默认 | `order-schema.sql:1-2,20`、`user-schema.sql:2,19`、`product-schema.sql:2,29` |
| 7 | **4.8** CI "仅被 README 提及，未深入查看" | **不成立**。`.github/workflows/ci.yml` 实际存在且完整：backend（compile+test）、frontend（frozen-lockfile + 递归构建 + pnpm 缓存）、images（打包后构建 4 个 Docker 镜像）。真实弱点：compile/test 重复编译、无测试报告上传、无镜像推送与 layer 缓存、无依赖扫描 | `.github/workflows/ci.yml` |
| 8 | **4.2** 无 API 文档（"79 个文件无任何 Swagger 注解"） | **表述过重**。确无 springdoc 运行时文档，但 `docs/api-contracts/` 下有 **5 份手写 OpenAPI 3.0.3 契约**（auth/user/product/order/admin，672 行），含 security scheme、错误码响应、快照字段说明，质量不错。真实问题：契约手维护已有漂移（`POST /orders/{orderNo}/complete` 不在 order-api.yaml 中）、契约 README 自称"三个契约"实为五个、无契约-代码一致性校验 | `docs/api-contracts/`、`OrderController.java:62` |
| 9 | **1.4** 对账 Job 的 Feign "默认 OpenFeign 1s 超时"，应放宽到 5s+ | **方向反了**。Spring Cloud 2023.0.1 的 OpenFeign 默认 connectTimeout=10s / readTimeout=60s，不是过短而是过长：对账串行 100 条 × 60s 最坏卡一轮约 100 分钟。应为该 client 显式配 3-5s 超时 + fallbackFactory | `OrderClient.java:9`、各 `application.yml` 无 `feign:` 段 |
| 10 | **1.5** create 补偿失败应"直接抛 SYSTEM_ERROR" | **建议不成立**。create 在 catch 里已重抛原始异常给用户（`:93`），补偿失败发生在重抛之后，无处可抛。真正缺口是无告警/metrics 打点，建议补偿失败打点 + 同步重试一次，剩余交给对账闭环 | `OrderService.java:87-94,397-404` |
| 11 | **1.3** 注册应先 `selectOne` 预检唯一性 | **建议错误**。有唯一索引时"insert + 捕获 DuplicateKeyException 翻译为友好 409"是标准且推荐的做法——唯一索引才是并发唯一性的可靠裁判，预检 select 是 racy check-then-act。现状不应改 | `AuthService.java:37-41`、`user-schema.sql:17` |
| 12 | **1.3** `clearOtherDefaults` 缺 `(user_id, is_default)` 复合索引会全表扫 | **降级为非问题**。`idx_address_user (user_id)` 已把更新范围限定到单个用户的几行地址，复合索引收益趋近于零 | `AddressService.java:82-85`、`user-schema.sql:34` |

### 0.2 ⚠️ 校准清单（事实成立，但严重度或建议需修正）

1. **HS256 → RS256**（v1 定中危）：学习项目共享密钥 HS256 是合理选型，RS256 的信任域分离收益在此场景有限；短期真正要解决的是同一明文 secret 分发到 4 个容器的一致性风险（任一漂移即全 401）。
2. **"X-User-Id 信任，当前安全"**（2.2）：仅当前 compose 拓扑成立。compose 确未发布 8081-8083，但 **MySQL 3306(root/root)、Redis 6379(无密码)、Nacos 8848 都发布到了宿主机**；compose 网络内任意容器可伪造头；IDE 本地裸跑时服务端口直暴露。结论应表述为"仅内网拓扑下成立 + 需文档声明威胁模型"。
3. **"/internal/** 网关拦截是正确设计"**（2.2）：意图正确，但该过滤器分支**实际不可达**——路由谓词只匹配 `/api/v1/**`，外部 `/internal/**` 请求在路由匹配阶段就 404，根本到不了 GlobalFilter。真正的防护是"路由不覆盖 + 服务端口不发布"；过滤器分支保留作纵深防御即可，应加注释说明，避免后人误以为它在起作用。
4. **缓存击穿/雪崩/延时双删**（3.2）：风险成立但影响校准——miss 后只是主键单查代价低；TTL 按访问逐个回填天然散开，雪崩仅在重启预热场景成立。比它们更真实的新问题是 **evict 发生在事务提交前**（见 2.4 节 🆕-6）。
5. **StringRedisTemplate "不如" GenericJackson2JsonRedisSerializer**（3.2）：属选型口味而非缺陷。String + 显式 JSON 对单一 DTO 合法（Boot 的 ObjectMapper 原生支持 record），GenericJackson2 依赖 activateDefaultTyping 写入 @class，有自身的反序列化安全面。真实代价只是每个调用点包 try/catch 的样板。
6. **可观测性标 🔴 严重**（3.7）：对学习项目定 🟡 更合适（仅暴露 health 端点属实），但它确实是简历收益最高的单项投入，保留为高优先演进项。
7. **多实例定时任务重复执行**（3.3）：属实且代码注释、`docs/CONSISTENCY.md:159` 均已声明单实例假设。校准：重复执行**不破坏正确性**（autoComplete 逐行 CAS、reconcile 靠 restoreStock 幂等兜底），只是重复扫描与重复外呼。是有文档的正确取舍。
8. **Feign 缺超时/重试/熔断**（3.6）：缺显式超时配置与熔断属实；但"缺重试"是 `docs/DECISIONS.md` 记录的**有意正确决策**（下单必须确认实时库存，扣库存接口不应盲目重试），应显式写配置 `NEVER_RETRY` 并注释，而非当缺陷修。
9. **共享 DTO 应抽到 mall-common**（4.1）：order 手写 ProductDTO 是 DECISIONS.md 记录的有意决策（服务间只经 Feign 契约耦合，避免编译期依赖）。塞进 mall-common 会让所有服务共享全部 DTO，更差。真实缺口：无独立 api 契约模块 / 无契约生成 / 漂移靠人肉（已发生，见 0.1 #8）。
10. **实体改 @Data**（1.3/4.6）：应为 `@Getter/@Setter`——`@Data` 生成的 equals/hashCode 对 MyBatis 实体有坑。且项目实为"实体手写 + 日志 @Slf4j"的混合风格（mall-user 的 pom 甚至没有 Lombok 依赖，mall-product 有却未用于实体）；DECISIONS.md 记录过 DTO 改 record 导致主键回填失败的踩坑。建议统一而非简单加 @Data。
11. **"单测覆盖较好（含并发场景）"**（4.9）：48 个用例、覆盖 Saga 主要分支属实；但"并发"是 mock 模拟竞态（stub update 返回 0），无真实多线程测试。另发现两处**空心断言**：`OrderServiceTest:242,327` 的 `verify(orderMapper, never()).updateById(...)` 恒真（生产代码走 `update(null, wrapper)`），并未真正验证想验证的事。
12. **BCrypt strength 10 偏弱**（2.3）："10 偏弱"有争议（OWASP 下限），更有说服力的论据是**不一致**：种子 admin 哈希是 cost 12（`user-schema.sql:39` 的 `$2b$12$...`），而运行时注册用户走默认 cost 10——同一个系统两套强度。统一 `PasswordEncoder` Bean 并设 strength 12。
13. **GMV 吞 NumberFormatException**（1.5）：确认，但该分支几乎不可达（MySQL SUM(DECIMAL) 经 MyBatis 返回 BigDecimal）。改为 error 级 + 监控打点的低成本加固即可，不算真缺陷。
14. **backfillMissingImages 串行**（1.5）：成立但当前是单商品订单模型（CreateOrderRequest 仅一个 productId），missing 集合至多 1 元素，串行/并行今天无差异；扩展多商品时才成真问题。
15. **网关手工拼 JSON 响应**（1.2）：确认，但校准——当前 message 来自枚举常量（无引号），今天不会坏，属防御性改进；编码处理目前是正确的（显式 UTF-8）。

### 0.3 ✅ 确认保留的主要观点

JWT secret 静默兜底（P0 保留）、TTL 硬编码、校验异常只取第一条、ResultCode→HttpStatus if/else 硬编码、白名单硬编码违反 OCP（已核实白名单与实际接口面精确一致、无多放少放）、catch(Exception) 过宽、缺 CORS、缺 TraceId、缺 jti/aud/refresh、OPTIONS 透传、网关无限流、改密 409 语义不当、三段重复字段处理、双 BCrypt 实例、无审计日志、乐观锁紧循环无退避、状态字符串散落、嵌套三元、登录无失败锁定、密码仅 @Size(min=6)、内部接口无鉴权、商品写操作只信网关、缓存写后删顺序正确但缺注释、悲观场景缺互斥、TTL 固定、HikariCP 未配、依赖版本偏旧（且 3.2.x 已 EOL）、缺依赖漏洞扫描、缺 graceful shutdown、Dockerfile 简单、compose 无资源限制、缺 .dockerignore、缺集成测试、Nacos 仅用 discovery（有决策记录）、贫血模型（已有状态机雏形）、服务拆分/common 边界/RESTful 风格（v1 表扬全部属实）、六道防线文档与代码逐条一致（CONSISTENCY.md 经逐条核实）。

---

## 总体评价

| 维度 | v1 评分 | v2 评分 | 评语 |
|---|---|---|---|
| 代码质量 | 8.0 | 8.0 | 命名规范、Java 17 特性、分层清晰；确认存在少量空心测试断言与魔法值 |
| 安全性 | 6.5 | 6.5 | 推翻了字符集等错误指控，但新增内部接口入参校验缺失、无 CORS、改密后旧 token 全程有效等实锤；整体维持 |
| 性能与可扩展性 | 7.5 | 7.5 | v1 的多条性能指控不成立（索引其实存在），但新增 admin/统计索引缺口、事务内 evict、分页无上限等真实问题，得失相抵 |
| 架构与一致性 | 8.0 | 8.0 | 六道防线文档-代码一致性经逐条核实属实；但发现两道防线未覆盖的方向（见下），设计分与正确性分分开看 |
| **综合** | **7.5** | **7.5** | 学习/简历项目中的高质量完成度；v1 的 P0 有 1 条是误报，但换成了 2 条更真实的一致性 bug |

**一句话结论**：这份项目的"文档-代码同步度"和一致性设计在同类学习项目中属顶部水平，但本轮复核恰恰在最引以为傲的库存一致性上找到了两个 v1 没发现的真 bug（幂等吞异常双重扣库存、cancel 竞态 phantom 库存）——设计是对的，边界处理有洞。

---

## 一、高优先级问题（本轮复核后重排）

| 优先级 | 模块 | 问题 | 类型 |
|---|---|---|---|
| 🔴 P0 | product | `decreaseStock` 幂等表唯一键冲突被吞，但本请求扣减已生效 → 同 orderNo 双重扣库存 | 一致性 bug |
| 🔴 P0 | order | cancel"先回补、后 CAS"竞态：并发 pay 抢先 CAS 成功后，库存已回补且永不扣回 → phantom 库存 | 一致性 bug |
| 🔴 P0 | gateway | `@SpringBootApplication(scanBasePackages="com.mall")` 把 servlet 版 `GlobalExceptionHandler` 扫进 WebFlux 网关，可能把 404/503 统一转成 500 | 架构 bug |
| 🔴 P0 | common | JWT secret 静默兜底 + static 初始化三重问题（v1 P0 保留并加深） | 安全 |
| 🟡 P1 | order/product | Feign 显式超时（对账 client 3-5s）+ 补偿/对账失败告警打点 | 可靠性 |
| 🟡 P1 | product | 内部库存接口零校验：`count` 无 `@Min(1)`，负数直接增加库存；restoreStock 信任入参 count 而非 dedup 记录值 | 安全 |
| 🟡 P1 | order | 补索引：`order(created_at)`、`(status)`、`(status, updated_at)`、`stock_dedup_log(status, created_at)` + 对账查询补 ORDER BY + RESTORED 记录清理 | 性能 |
| 🟡 P1 | gateway | CORS 全仓缺失（不止网关）+ 登录接口限流/失败锁定（Redis 已在 compose 中） | 安全 |
| 🟡 P1 | product | 缓存 evict 移到 `TransactionSynchronization.afterCommit` + 击穿互斥重建（好的学习练习） | 性能 |
| 🟢 P2 | 整体 | 可观测性（prometheus 端点 + 网关日志 + TraceId）——v1 定 P0，校准为高价值演进项 | 可观测 |
| 🟢 P2 | common/user | 状态枚举化（ProductStatus/DedupStatus/OrderStatus 贯通）+ PasswordEncoder 统一 Bean(strength 12) + token_version 吊销 | 代码质量 |

> **✅ 修复记录（2026-08-31，v2 评审后）**：上表中 P0 四项已全部修复，P1 五项已修复（Feign 超时、内部接口校验+回补量取记录值、索引迁移 zz-migration-v1.4.sql、CORS、evict 移 afterCommit 已随 P0-1 一并完成）。**暂缓**：登录限流/失败锁定（需为 mall-user 引入 Redis 依赖并做产品设计）、缓存击穿互斥重建（按报告定位为学习练习）、可观测性（P2 演进项）、PENDING 超时自动关单（P2）。修复内容：`decreaseStock` 改幂等占位先行（唯一键先仲裁，抢锁失败方零净效果，附回归测试）、`cancel` 反转为先 CAS 后回补并配套对账按「订单不存在或已 CANCELLED」回补（内部接口 `/exists` 升级为 `/state`，附顺序锁定测试）、`GlobalExceptionHandler` 限定 SERVLET 栈、`JwtUtil` 移除兜底密钥改为启动 fail-fast（`JwtSecurityInitializer` + 全服务 `mall.jwt.secret` 接线）。全部 59 个测试通过（mvn test，5 模块 SUCCESS）；同步更新 CONSISTENCY.md / DECISIONS.md / README.md / zz-migration-v1.4.sql。

---

## 二、分模块详评

> 标记说明：✅ = v1 观点经核实保留；✏️ = v1 结论已修正；🆕 = 本轮新增发现。

### 2.1 mall-common（v1 8.5 → 8.0）

**✅ JWT secret 静默兜底（P0 保留）** — `JwtUtil.java:12-13` `getOrDefault("JWT_SECRET", "mall-learning-local-demo-secret-key-...")` 缺失时静默降级。v2 加深：`KEY` 是 `static final`（`:14`），改成"缺失即失败"的正确形态是 `@ConfigurationProperties` Bean——否则失败形态是首次调用时的 `ExceptionInInitializerError` 而非清晰启动错误；且 `JwtUtilTest` 全程依赖兜底密钥才能跑，整改需同步重构测试。另注意：兜底默认值与 compose 注入值**不一致**（`docker-compose.yml:41,59,77,99` 用的是 `local-demo-jwt-secret-change-me-...`），所以"漏配 env"在 compose 下表现为全 401（难排查但不致命），本地裸跑下才是静默降级。

**🆕 static 初始化的另外两个问题** — (a) 自定义 secret 不足 256 位时抛 `WeakKeyException` 包成 `ExceptionInInitializerError`，报错晦涩；(b) KEY 永久缓存无法轮换。一个 `@ConfigurationProperties` Bean 一并解决上述全部。

**✅ TTL 24h 硬编码** — `JwtUtil.java:15`；带 ttl 的重载仅测试使用（`JwtUtilTest.java:37`）。

**✅ 校验异常只取第一条** — `GlobalExceptionHandler.java:36-39` `findFirst()`；另注意 `getDefaultMessage()` 可能为 null，且丢弃了类级约束错误（应 `getAllErrors()`）。

**✅ HttpStatus 映射 if/else 硬编码** — `GlobalExceptionHandler.java:18-29`。建议在 `ResultCode` 枚举上加 `httpStatus` 属性，可同时消除映射问题与 `ResultCode.java:4-12` 的编码体系混用（HTTP 同名码与 5 位业务码混放、UNAUTHORIZED=401 而 FORBIDDEN=40301 规则不一）。

**✏️ BizException 序列化/栈优化（v1 定🟢，维持🟢但校准）** — `BizException.java:5` 确无 `serialVersionUID`、未覆盖 `fillInStackTrace`；学习项目中前者无实际影响（不跨进程序列化），后者仅高频当流程控制时才有意义。低优先级。

### 2.2 mall-gateway（v1 7.5 → 7.0）

**🆕 servlet 异常处理器被扫进响应式网关（P0）** — `GatewayApplication.java:6` `scanBasePackages = "com.mall"` 会把 `GlobalExceptionHandler`（`@RestControllerAdvice` + `@ExceptionHandler(Exception.class)`，`GlobalExceptionHandler.java:14,43-48`）注册进 WebFlux 环境。WebFlux 支持 @ControllerAdvice 且优先于默认错误处理，网关的 `ResponseStatusException`（无路由 404、LB 找不到实例 503 等）可能被统一转成 `500 {"code":500,"message":"系统繁忙"}`，破坏网关状态码语义并掩盖真实错误。（扫描关系是代码事实；404→500 为 WebFlux 行为的静态推断，未实跑验证。）修复：advice 加 `@ConditionalOnWebApplication(type = SERVLET)`，或缩小网关扫描范围。

**✅ 白名单硬编码（维持，但补充核实结论）** — `JwtAuthGlobalFilter.java:34-38`。已逐一核对后端接口面：白名单与 `ProductController`（两个公开 GET + 受保护写操作）、`CategoryController`（仅 GET 公开）**精确一致，无多放也无少放**；`productWrite` 用 `startsWith` 同时覆盖 POST 根路径与 PUT/DELETE `/{id}`，全程 fail-closed。问题仅在 OCP：建议配置化到 yml。🆕 顺带：`:37` 每请求 `Pattern.compile`，应提为静态常量；尾斜杠 `/api/v1/auth/login/` 不命中白名单会 401（安全无虞但行为意外）。

**✅ catch(Exception) 过宽 + 🆕 网关零日志** — `JwtAuthGlobalFilter.java:62-64` 把 NPE 等 bug 也吞成 401；且全类无任何 log，4 个拒绝分支（`:32,:44,:53,:63`）静默返回——secret 配错导致 401 激增时完全无线索。应改捕 `JwtException` + 加 `@Slf4j` 记 warn/debug。

**✅ 手工拼 JSON / ✅ 缺 CORS / ✅ 缺 TraceId / ✅ 无限流 / ✅ OPTIONS 透传** — `:71,80` 拼串（当前安全、防御性改进）；全仓 grep 无任何 cors 配置（网关和下游都没有，接前端必挂 preflight）；建议网关统一 globalcors 并短路 preflight；网关 pom 无 redis-rate-limiter 依赖，compose 已有 Redis，给 `/api/v1/auth/login` 加 RequestRateLimiter 是低成本高收益点。

**✏️ /internal/** 拦截"正确设计"** — 见 0.2 #3：分支实际不可达（路由只匹配 `/api/v1/**`），保留为纵深防御 + 加注释。

**✅ Token 缺 jti/aud/iss、无 refresh、无吊销** — `JwtUtil.java:26-38` 仅 sub/username/role/iat/exp。实际后果具体化：改库中 role 后旧 token 最长 24h 仍带 ADMIN（filter `:52` 只信 token 里的 role）；改密后旧 token 全程有效（见 2.3 🆕）。compose 里已有 Redis，jti 黑名单/token_version 是合适的学习扩展。

### 2.3 mall-user（v1 8.0 → 8.0）

**✅ 改密错误码语义（细化）** — `UserService.java:47-48` 旧密码错抛 409。v2 细化：旧密码错是凭据校验失败，应 400（用户已持合法 token，401 反而混淆）；而 `:50-51` "新密码与当前相同"用 409 恰当。两条拆开处理。

**✅ 三段重复字段处理** — `UserService.java:31-39`，抽 `setIfPresent(value, setter)` 即可。

**✏️ 注册并发穿透（建议撤销）** — 见 0.1 #11：现状是标准做法，不改。

**✅ 双 BCrypt 实例 + 🆕 强度不一致** — `UserService.java:18`、`AuthService.java:22`；种子 admin 是 cost 12、运行时注册是 cost 10（`user-schema.sql:39` vs 默认构造）。统一 `@Bean PasswordEncoder(strength=12)` 一并解决。

**✅ 无审计日志** — user 模块全部 service/controller 无任何 logger（对照 ProductService 有 `@Slf4j`）。注册/登录/改密至少 info 事件 + 失败 warn。

**🆕 改密后旧 token 仍有效 24h** — `UserService.java:45-56` 只更新哈希；无吊销机制。加 token_version 塞 claim 校验，或 jti 黑名单。

**✅ 登录无失败锁定 / ✅ 密码仅 @Size(min=6)** — `AuthService.java:45-51`、`RegisterRequest.java:8`（"123456" 可过，username 也无字符集 pattern）。注意修复前置：mall-user 的 pom **没有 redis 依赖**，做锁定需先引入。

**✏️ clearOtherDefaults 索引（降级为非问题）** — 见 0.1 #12。

**✏️ Lombok（校准）** — 见 0.2 #10。实体 `@Getter/@Setter`，勿 `@Data`。

**🆕 默认地址不变量可被删除破坏** — `AddressService.java:34` "首条地址必为默认"用 `countByUser(userId)==0` 判断，但 `delete`（`:64-67`）删除默认地址后不转移默认；之后再建地址因 count>0 不强制默认，若请求也未选默认 → 列表无默认地址，破坏 `:38` 注释声称的下单页保证。改判据为 `selectCount(user_id and is_default=1)==0` 或删除默认后自动转移。

**🆕 内部接口信任链** — `InternalUserController.java:15-28` 无鉴权（类注释 `:11-14` 自述依赖网关拦截+内网隔离）。属主校验本身正确且返回 404 防枚举（`AddressService.java:88-94`，好设计）。与 product 侧一起统一加内部共享密钥头（见 2.4）。

### 2.4 mall-product（v1 8.5 → 8.0，发现 2 个一致性 bug）

**🔴🆕 幂等表吞异常导致同 orderNo 双重扣库存** — `ProductService.java:124-139`。时序：并发同 orderNo（Feign 重试/消息重投/重放，恰是幂等表要防的场景）A 扣减成功并插入 dedup；B 在版本冲突后重试 `selectById`（拿到 A 已提交的新版本）**再次扣减成功**，插入 dedup 撞唯一键 → catch 块把"我这次扣减已生效"误判为"已被并发覆盖"（`:134-135` 注释结论错误）→ return true。净效果：库存扣两次、dedup 只记一次。修复任选：① catch 内回补自己的扣减（`stock += count`）；② **dedup insert 前置**到扣减前（同事务内唯一键先仲裁，冲突即异常回滚，零净效果）；③ 改单条条件 UPDATE `SET stock=stock-? WHERE id=? AND stock>=?` 判 affected rows（同时消灭紧循环重试问题）。

**✅ 乐观锁配置完整（亮点确认）** — `MybatisPlusConfig.java:16` 正确注册 `OptimisticLockerInnerInterceptor`（最常被漏的一步）、`Product.java:23-25` `@Version`、create 显式置 0（`ProductService.java:84`）、四条路径全有冲突检测并翻译 409。

**✅ 紧循环重试无退避** — `ProductService.java:117-143` for 3 次重试无 sleep。理论活锁成立、实践窗口小；采纳 ③ 方案后循环整个消失，优于加随机退避。

**✏️ restoreStock "非原子/自调用失效"（推翻）+ 🆕 残留加固点** — 见 0.1 #2。真正值得做的三件小事：(a) 幂等闸门改为条件翻转 `UPDATE stock_dedup_log SET status='RESTORED' WHERE order_no=? AND status='DEDUCTED'` 判 affected==1 再动库存——与隔离级别无关的更强保护（当前 select-then-update 在弱隔离/特定交错下理论上存在 TOCTOU 窗口，默认 RR 下由 @Version 兜底）；(b) 回补量用 `dedupLog.getCount()` 而非入参 count（`:148,158`，当前调用方恰好传对，多商品化后会写歪）；(c) 校验入参 productId/count 与 dedup 记录一致。

**🆕 内部库存接口零校验** — `InternalProductController.java:42` `StockDecreaseRequest(int count, String orderNo)` 无约束注解、两个端点无 `@Valid`；`ProductService.java:119` 只查 `stock < count`，**count 为负直接增加库存**且通过校验；orderNo 为 null 生成坏 SQL。至少 `@Min(1)` + `@NotBlank` + `@Valid`。加上内部共享密钥头作纵深防御（库存是资金路径）。

**✅ 缓存写后删顺序正确 / 🆕 evict 在事务提交前** — 读路径 cache-aside 正确、失败一律 catch+warn 退化为 DB（好姿态）；但 `decreaseStock`/`restoreStock` 的 evict（`ProductService.java:137,140,168`）发生在 `@Transactional` 方法体内、**提交之前**，并发读可把旧值重新灌回缓存，与"evict 失败旧值存活一个 TTL"（✅ `:198-204`，维持）叠加。移到 `afterCommit` 回调。

**✅ 热点击穿 / TTL 固定（校准）** — 见 0.2 #4：影响有限，互斥重建/逻辑过期作为学习练习保留；TTL 加 `ttl + random(0~60s)` 一行即可。

**✅ 状态字符串散落 + 嵌套三元** — `ProductService.java:82,129,151,175,186,209`、`CategoryService.java:76-79`、`OrphanDeductionReconcileJob.java:67`。抽 ProductStatus/DedupStatus 枚举；顺带堵住 `:186` 任意非法字符串静默变 OFF_SALE 的行为。DB TINYINT ↔ entity Integer ↔ DTO String 的三重表示（🆕）建议集中到枚举转换一处。

**🆕 商品不校验 categoryId 存在** — `ProductService.java:178-187` 直接落库，schema 无外键；可造出指向不存在分类的商品，且与 `CategoryService.delete` 的检查存在竞态产生悬挂引用。

**🆕 detail() 的 "null" 哨兵是死代码 + 无负缓存** — `ProductService.java:60` 判断 `!cached.equals("null")` 但全项目无处写入 "null"；不存在商品（网关公开可枚举的 id）每次穿透 DB。要么写入短 TTL null 哨兵让判断有意义，要么删掉条件。

**🆕 list() 分页无上限** — `ProductController.java:31-34` size 无 max；`ProductService.java:49` 排序无 id 次级键（同刻创建分页不稳定）。

**✏️ 对账 Job Feign 超时（方向修正）+ 🆕 对账扫描三连** — 见 0.1 #9（默认 10s/60s 不是 1s，风险是过长）；另：对账查询 `eq(status).lt(created_at).last("LIMIT 100")` 无 `(status, created_at)` 索引、无显式 ORDER BY、RESTORED 记录永不清理随订单量膨胀（`Job.java:66-69`、`product-schema.sql:31-40`）。

**🆕 对账"不确定答案"防御分支语义错误** — `OrphanDeductionReconcileJob.java:94-99`：`response == null || code != 0` 一律"视为订单不存在"→ 回补。当前该分支实际不可达（错误都映射为非 2xx → Feign 抛异常走 catch 跳过），但这是地雷：未来谁把内部接口改成 200+错误码，就会**误回补存在订单的库存**。应改为"非确定答案一律 skip"。

**✅ 对账 Job 工程质量（亮点确认）** — 单条失败不中断整轮、顶层兜底防调度线程死亡、stale 窗口避让、参数全外置、三条路径含 Feign 异常路径全有测试。

**✅ 商品写操作只信网关（保留+声明）** — `ProductController.java:45-59` 无服务内角色校验，ADMIN 判断完全在网关。学习项目可接受，加注释声明信任边界 + 内部密钥头兜底。

**✏️ CategoryService 删分类检查（推翻 v1 性能指控）** — 见 0.1 #4。反向真实问题：商品侧不校验 categoryId（见上 🆕）。

### 2.5 mall-order（v1 7.5 → 7.5，取消 1 条误报 P0，新增 1 条 P0）

**🔴🆕 cancel"先回补、后 CAS"竞态 → phantom 库存** — `OrderService.java:106-107`：`restoreStockOrThrow` 成功（库存 +N、dedup→RESTORED）**之后**本地 CAS `PENDING→CANCELLED` 才执行。若并发 pay 在两步之间抢先 CAS 成功，cancel 抛 409 中止，但库存已回补且无任何机制扣回：pay 不动库存、dedup=RESTORED 使对账永不再处理、restoreStock 幂等条件是"仍为 DEDUCTED"。结果：PAID 订单 + 已回补库存，履约后净多 N 件 phantom 库存。修复：顺序反转为"先本地 CAS 后远程回补"（回补失败靠对账，对账条件需从"订单存在即跳过"改为结合订单状态判断，见下）。这是 create"先远程后本地"设计的镜像场景——v1 没看到的盲区。

**✏️ cancel 事务问题（推翻）** — 见 0.1 #1。TransactionTemplate 短事务策略在 create/cancel 贯彻一致，是亮点。

**🆕 缺"超时未支付自动取消"，PENDING 永久锁死库存** — autoComplete 只处理 SHIPPED（`:156-158`）；对账 Job 对订单存在的 DEDUCTED 记录直接跳过（`OrphanDeductionReconcileJob.java:73-75`）。v1 技术债表已登记"自动关单不做"，但 v2 升级其影响定性：不只是缺功能，而是叠加无限流后构成库存资源耗尽向量，且与对账逻辑产生"已取消但回补失败的单被永久跳过"的交互盲区。

**✅ 状态机 CAS（亮点确认）** — `OrderService.java:163-173,291-305` 条件更新 + 影响行数校验；auto-complete 与手动确认收货并发时双方目标态一致、结果幂等，注释论证准确。✅ 快照完整（receiver `:361-363`、item name/price/image `:371-375`，v1.3 迁移含存量回填）。✅ **金额服务端计算确认安全**（`:358` = price×count，请求体无金额字段，无信任前端金额问题）。✅ Feign 不确定结果处理（`:331-350,511-523` 超时后查 dedup 状态决定继续/补偿而非盲补）——多数学习项目没有这层。

**✅ 订单号（校准）** — 见 0.1 #3；保留"碰撞善后重"作为小改进点。

**✅ 归属校验/防枚举（确认）** — cancel/pay/complete 走 `requiredOrder`（`:436-442`，非本人返回 NOT_FOUND）；ship 走 `requiredAdminOrder`；网关对 `/api/v1/admin/**` 强制 ADMIN。

**✏️ 索引问题（重定向）** — C 端列表索引完整（0.1 #5）；真实缺口：adminList 的 status 过滤与排序、autoComplete 的 (status, updated_at)、统计接口 created_at 范围（`OrderMapper.java:17-31` 四条聚合 SQL 均无索引支撑且无缓存——✅ 实现上聚合下推 DB 是对的，缺的是索引与短 TTL 缓存）。

**✅ 支付防重放（校准为非问题）** — 状态机 CAS 使重复 pay 第二次落 `assertTransition` 失败 409，无副作用；接入真实支付网关时才需要幂等 Token/回调验签。**✅ CreateOrderRequest 无幂等保护（确认成立）**：两次提交生成两个 orderNo → 扣两次库存建两单；建议 client-token + (user_id, token) 唯一约束。

**🆕 测试空心断言** — `OrderServiceTest.java:242,327` `verify(orderMapper, never()).updateById(...)` 恒真（生产代码走 `update(null, wrapper)`），未真正验证"状态保持/未越权写"。stats/backfill/adminList 零覆盖。其余 19 例对 Saga 分支的覆盖仍属良好。

**🆕 其他** — `OrderMapper.java:15,25` SQL 字面量 'CANCELLED'/'PAID' 与枚举无编译期绑定；分页 size 无上限（`OrderController.java:40-41`）；下单价来自 10min TTL 的商品缓存（改价后短窗旧价成交，订单内部自洽无资损，属应文档化取舍）；GMV 口径含 PENDING（注释已声明，业务选择）。

### 2.6 横切面：工程、部署与架构

**✅ 版本偏旧（加重）** — `pom.xml:10,31-33`。v2 加重定性：Spring Boot 3.2.x OSS 已于 2024-12-31 EOL，3.2 线不再有 CVE 补丁；"升 3.2 最新 patch"意义有限，应直接评估 3.3+/3.5.x。学习项目低优先，但面试会被问"为什么用 EOL 版本"。✅ 缺 dependency-check/dependency-review。✅ HikariCP 未配（面试高频，至少配 maximum-pool-size）。

**✅ 可观测性（降级 🟡）** — 仅 health 端点；加 `micrometer-registry-prometheus` 是一天工作量、简历收益最高单项。

**✅ 定时任务单实例假设（校准）** — 见 0.2 #7：已文档化，正确性不破坏，扩多实例前加 ShedLock 即可。

**✏️ API 文档（修正）** — 见 0.1 #8。补齐方向：CI 加 openapi-diff 守门或 springdoc 导出对齐；顺带修 `api-contracts/README.md:26` 的"三个契约"计数错误。

**✏️ DTO 复制（修正）** — 见 0.2 #9：两份 ProductDTO 逐字节相同是有意决策；缺口在契约漂移守门而非"抽到 common"。

**✅ Nacos 仅用 discovery（确认+核实）** — DECISIONS.md 记录的有意取舍；SCA 2023.0.1.0 自带 nacos-client 2.3.2 与 compose 的 server 同代匹配，gRPC 9848 已暴露（常见遗漏点，此项目做对了）。

**✅ Dockerfile/compose（保留+🆕）** — 4 个 Dockerfile 相同的 5 行（无多阶段/root/无 JVM 参数/无 TZ）；compose 无资源限制；无 .dockerignore；graceful shutdown 未启用——全部维持 v1 建议。🆕 新增：MySQL 健康检查 `mysqladmin ping -h localhost` 走 socket，首次初始化期 entrypoint 的临时服务器 skip-networking 但 socket 可达 → 可能提前判定 healthy，依赖服务（`depends_on: service_healthy`）启动即连接失败，改 `-h 127.0.0.1` 强制 TCP；gateway `depends_on` mysql+redis 但网关两者都不用（真依赖只有 nacos）；v1.3 迁移脚本 `UPDATE order_item JOIN mall_product.product`（`zz-migration-v1.3.sql:26-28`）违反自身"跨库不 join"原则，仅单 MySQL 实例成立且未声明前提；基础 schema 种子 INSERT 非幂等（zz- 迁移反而做了幂等）。

**✅ CI（修正 v1）** — 存在且三 job 完整；改进点：backend job compile+test 重复编译、无测试报告上传、images 无推送与 layer 缓存、无依赖扫描。

**✅ 测试（校准）** — 48 例纯 Mockito（快），覆盖面好； hollow 断言见 2.5；缺 Testcontainers/@WebMvcTest/WireMock（对以一致性为卖点的项目，扣减-补偿-对账链路的集成测试是最值得补的短板，DECISIONS.md 已记录"不真实启动完整 Docker 栈"取舍）。

**✅ 文档与代码同步度（亮点，v1 未评）** — DECISIONS.md 37 条决策均带备选+理由、多次记录真实踩坑；CONSISTENCY.md 六道防线经逐条核实与代码一致（E2E 表格是一次性手工记录、无自动化复跑，唯一水分）。

**🆕 前端概况（本轮首评）** — pnpm workspace 三包结构清晰：`@mall/shared` 纯 TS 类型包（与后端契约手工对齐，v1.3 快照字段已同步）、vue3-admin（Element Plus + echarts）、vue3-mall（Vant），build 带 `vue-tsc --noEmit` 类型门槛，CI frozen-lockfile + 缓存。中上水平；缺口：无 ESLint/Prettier/任何前端测试；`pnpm-workspace.yaml` 里 `allowBuilds: vue-demi: "set this to true or false"` 是模板占位残留（非合法字段/值）。

---

## 三、亮点（v1 确认 + v2 新增）

1. **状态机 CAS**（`OrderService.transition`）— v1 表扬属实，并发正确性论证准确。✅
2. **跨服务一致性"六道防线"** — 文档-代码逐条一致属实 ✅；v2 补充：防线覆盖 create-crash 与对账闭环，但 **cancel/pay 竞态与幂等吞异常是两个未覆盖方向**（见 P0），修复后该设计才真正闭环。
3. **TransactionTemplate 短事务在 create/cancel 贯彻一致** ✅（v1 怀疑 cancel 违背此原则，系误读）。
4. **Feign 不确定结果处理**（超时后查 dedup 状态再决策，不盲目补偿）— v1 未发现的亮点。🆕
5. **金额服务端计算、请求体无金额字段** — 无信任前端金额漏洞。🆕
6. **网关先剥 `X-User-Id` 再注入可信值，且鉴权后移除 `Authorization`** — 防伪造 + 防凭证下漏双细节 ✅🆕。
7. **白名单与接口面精确对齐、全程 fail-closed** 🆕；**越权返回 404 防资源枚举** ✅。
8. **乐观锁三件套完整**（拦截器注册 + @Version + 全路径冲突翻译 409）🆕；**幂等以 DB 唯一键为并发裁判**（选型正确）✅。
9. **对账 Job 工程质量**（逐条容错、顶层兜底、stale 窗口、参数外置、异常路径测试）🆕。
10. **文档-代码同步度**（DECISIONS.md 踩坑记录、CONSISTENCY.md 可逐条验证、SQL 迁移幂等工程化）🆕。
11. 统一 Result/ResultCode/BizException/GlobalExceptionHandler、Java 17 record/switch、服务拆分与 mall-common 边界 — v1 表扬全部属实 ✅。

---

## 四、技术债务表（v2 修订版）

| 项 | 状态 | 备注（v2 修订） |
|---|---|---|
| 支付 | 模拟 | README 已说明；防重放靠状态机 CAS，当前安全 |
| 购物车/优惠券/物流 | 不做 | 已知简化 |
| 自动关单 | 不做 | v2 升级定性：与对账逻辑交互产生库存锁死/回补盲区（2.5 节） |
| Redis | 仅商品详情缓存 | 且 evict 在事务提交前（P1） |
| Spring Security | 未引入 | 仅自定义 JWT Filter；角色口径集中网关一处，可接受 |
| 可观测性 | 仅 health | v1 定 🔴 → v2 定 🟡；简历收益最高演进项 |
| Sentinel/Resilience4j | 未引入 | 缺熔断；缺重试是有意正确决策（DECISIONS.md） |
| Nacos Config | 未启用 | 有决策记录的取舍 |
| API 文档 | **v1 修正**：有 5 份手写 OpenAPI 契约 | 缺运行时文档与漂移守门（complete 接口已漂移） |
| CI | **v1 修正**：存在且三 job 完整 | 缺测试报告/镜像推送/依赖扫描 |
| 字符集 | **v1 撤销**：utf8mb4 全量正确 | — |
| ~~restoreStock 自调用失效~~ | **v1 撤销** | 误报，事务与乐观锁配置完整 |
| 前端 | v2 新增 | 工程化中上；无 lint/测试；workspace 配置有模板残留 |

---

## 五、评审结论

这是一份完成度较高的**学习/简历项目**。v2 复核的价值在于双向修正：一方面撤销了 v1 的 1 条误报 P0 和多条不成立的性能/配置指控（索引、字符集、CI、API 文档其实都做得不错），另一方面在最核心的一致性设计里找到了两个 v1 未发现的真 bug——**幂等表吞异常导致的双重扣库存**与 **cancel 先回补后 CAS 的竞态 phantom 库存**。设计的骨架是对的，需要的是边界补洞而非重构。

**建议的演进路线（v2 修订）**：
1. **第一阶段（修复，3-5 天）**：两个一致性 P0（dedup 前置/条件 UPDATE；cancel 先 CAS 后回补 + 对账按状态判断）、网关异常处理器隔离、JWT secret fail-fast（@ConfigurationProperties + 测试重构）、内部接口入参校验、Feign 显式超时。
2. **第二阶段（加固，1-2 周）**：补索引与对账扫描治理、CORS + 登录限流/锁定、缓存 evict 移 afterCommit + 击穿互斥、补偿/对账告警打点、prometheus 端点 + 网关日志、契约漂移守门。
3. **第三阶段（演进，1 个月+）**：token_version/jti 吊销 + refresh token、Testcontainers 集成测试（扣减-补偿-对账全链路）、PENDING 自动关单、多实例 + ShedLock、Boot 升级出 EOL 版本、前端 lint/测试。

---

> **v2 评审方法说明**：本版由 Agent Teams 4 个并行评审 Agent（mall-common+gateway / mall-user+product / mall-order / 横切面）独立精读全部源码后，主 Agent 逐条比对 v1 报告汇总而成；所有推翻 v1 的关键结论（cancel 无 @Transactional、restoreStock 事务生效、order_no 唯一索引存在、utf8mb4、CI 存在）均经主 Agent 亲自读取源码二次确认。v1 报告由主 Agent 单独完成（当时 subagent 调用全部失败）。
