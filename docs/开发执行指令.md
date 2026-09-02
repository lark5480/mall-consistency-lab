# Mall-Consistency-Lab 开发执行指令（v2.0；原名 Mall-Learning）

> **本指令面向执行 Agent**：你将独立完成一个用于简历展示的个人电商项目（mall-consistency-lab，原名 mall-learning）的全部代码与文档生成工作。请逐字阅读「总体执行规则」后再开始任何步骤。
>
> **产品需求**：请先阅读 `docs/PRD.md` 了解产品背景、用户角色、功能需求与验收标准。本指令只负责技术实现与执行流程。

---

## 0. 执行环境与前置条件

1. 本地开发假设环境：JDK 17、Maven 3.9+、Node 20.x、pnpm 9.x、Docker（含 docker compose v2）。
2. **生成优先原则**：本任务的首要交付物是完整、无占位符的代码与文档。若当前环境缺少上述任一工具：
   - 不要花时间安装环境，跳过对应自检；
   - 在 `docs/DECISIONS.md` 记录「跳过项 + 原因」；
   - 在步骤报告中将对应 gate 标注为「未自检（环境缺少 X）」。
3. 若工具齐全，必须真实执行各步骤 gate（见 1.4），不得虚报通过。

---

## 1. 总体执行规则（必须遵守）

### 1.1 决策日志（全程维护）

从第一步开始维护 `docs/DECISIONS.md`，记录你做的**每一个自主决定**，每条格式：

```
- [步骤N] 决定：<做了什么选择> | 备选：<还考虑过什么> | 理由：<为什么>
```

凡是本指令标注【自主决策】的条目，以及任何本指令未明确、由你自行补全的细节，都必须记录。

### 1.2 卡住策略

- 同一问题尝试 **2 次**仍失败：停止重试，在 `docs/DECISIONS.md` 记录「问题 / 已尝试方案 / 最终折中方案」，采用最保守的可行方案继续。
- 不允许因单点阻塞终止整体流程；也不允许静默跳过不记录。
- 唯一例外：某 gate 失败且后续所有步骤都依赖它时，在报告中显著标注，然后继续完成所有不依赖它的部分。

### 1.3 并行执行与降级

- 指令中的「并行 / Agent Teams」只表达**任务间无依赖**，不要求真实并行。
- 若你的运行时不支持并行子代理，按指令中列出的顺序**串行**执行，结果等价。
- 有依赖关系的任务（如「读契约 → 生成前端代码」）必须严格按依赖顺序执行。

### 1.4 每步 gate（验收门槛）

每个步骤结束前必须执行该步骤声明的 gate。gate 结果只有三种：`通过` / `失败（原因）` / `未自检（环境缺少 X）`，如实写入步骤报告。**禁止 gate 失败后不修复、不记录就进入下一步。**

### 1.5 步骤报告模板（固定格式）

每完成一步，输出：

```
### 步骤 N 总结报告
- 生成文件：<绝对路径清单>
- 关键决策：<决策 + 理由，须已同步至 docs/DECISIONS.md>
- 自检结果：<逐项列出 gate 结果>
- 遗留问题：<无 / 具体问题>
```

### 1.6 代码规范红线

- 所有代码必须是可编译/可运行的完整代码，**禁止占位符、省略号、TODO 顶替实现**。
- Java 代码必须包含完整 import 语句；Vue 组件一律 `<script setup lang="ts">`。
- 单个文件超过 200 行必须拆分。
- 契约文件必须是合法 YAML，可被 Swagger UI 直接加载。
- 所有配置（数据库、Redis、Nacos 地址、JWT 密钥等）通过环境变量注入并给本地默认值，**禁止硬编码**。

---

## 2. 技术栈版本锁定表

| 类别 | 组件 | 版本 |
|---|---|---|
| 后端 | Java | 17 |
| 后端 | Spring Boot（parent） | 3.2.4 |
| 后端 | Spring Cloud | 2023.0.1 |
| 后端 | Spring Cloud Alibaba | 2023.0.1.0 |
| 后端 | MyBatis-Plus | 3.5.5（使用 `mybatis-plus-spring-boot3-starter`） |
| 后端 | jjwt | 0.12.5（使用 0.12 API：`Jwts.builder().signWith(key)` / `Jwts.parser().verifyWith(key)`） |
| 前端 | Node / pnpm | 20.x / 9.x |
| 前端 | Vue / vue-router / pinia | 3.4.x / 4.3.x / 2.1.x |
| 前端 | TypeScript / Vite | 5.4.x / 5.2.x |
| 前端 | axios | 1.6.x |
| 前端 | Element Plus（admin）/ Vant 4（mall） | 2.7.x / 4.8.x |
| 基础设施镜像 | MySQL / Redis / Nacos | mysql:8.0.36 / redis:7.2-alpine / nacos/nacos-server:v2.3.2 |
| 基础设施镜像 | 服务运行镜像 | eclipse-temurin:17-jre-alpine |

版本已锁定，不得擅自升降级。若因依赖获取失败必须替换版本，在 `docs/DECISIONS.md` 记录实际版本与原因。

---

## 3. 架构约束与简化边界

### 3.1 模块与端口

| 模块 | 职责 | 端口 | base package |
|---|---|---|---|
| mall-gateway | 网关：路由、JWT 全局鉴权、屏蔽 /internal/** | 8080 | `com.mall.gateway` |
| mall-user | 用户注册/登录、用户信息 | 8081 | `com.mall.user` |
| mall-product | 商品 CRUD、库存扣减 | 8082 | `com.mall.product` |
| mall-order | 订单创建/查询/模拟支付 | 8083 | `com.mall.order` |
| mall-common | 统一响应体 `Result<T>`、全局异常处理器、JwtUtil、通用常量 | 被依赖，无端口 | `com.mall.common` |
| vue3-admin | B 端管理后台（Element Plus） | 3000 | — |
| vue3-mall | C 端用户商城（Vant 4） | 3001 | — |
| shared | 前端共享类型（**手写**，与契约逐字段对齐；不引入 openapi-generator 等代码生成工具） | — | — |

### 3.2 数据库与注册中心

- 三个独立库：`mall_user`、`mall_product`、`mall_order`，各服务只连自己的库。
- Nacos 地址通过环境变量 `NACOS_ADDR` 注入：本地默认 `localhost:8848`，docker-compose 内覆盖为 `nacos:8848`。

### 3.3 统一响应与对外标识

- 所有 HTTP 响应（含错误）统一包裹 `Result<T>`：`{ "code": int, "message": string, "data": T }`；成功 code=0。契约文件必须体现该包装（定义 Result schema）。
- 订单对外标识一律使用 `orderNo`（string，全局唯一），数据库自增主键 id 不对外暴露。

### 3.4 简化边界（明确不做 / 模拟）

| 项 | 决策 |
|---|---|
| 支付 | **模拟支付**：创建订单后 status=PENDING；提供模拟支付接口 `POST /api/v1/orders/{orderNo}/pay` 将其置为 PAID。不接任何真实支付。 |
| 收货地址 | **无地址服务/表/API**。C 端前端内置静态地址数组（≥2 条，含 id/收货人/电话/完整地址）；order 表保留 `address_id` 字段，引用前端静态数据。 |
| Redis | 仅用于一处：product-service 缓存商品详情，TTL 10 分钟，商品修改/删除时主动失效。其余场景不用。 |
| 购物车 / 优惠券 / 物流 | 本期不做。 |
| 角色权限 | 不区分角色：B 端商品增删改接口不做权限校验，在文档中注明「学习项目简化」。 |
| 订单状态 | 枚举保留 PENDING/PAID/SHIPPED/COMPLETED，本期实际只流转 PENDING→PAID。 |

### 3.5 服务间调用契约（仅允许 order → product）

网关必须屏蔽外部对 `/internal/**` 的访问。内部接口签名固定如下：

```
GET  /internal/products/{productId}               -> Result<ProductDTO>
POST /internal/products/{productId}/stock/decrease -> Result<Boolean>
     请求体: { "count": int, "orderNo": string }
     要求: 以 orderNo 为幂等键，重复调用返回首次结果
```

### 3.6 网关路由与白名单

- 路由：`/api/v1/auth/** → mall-user`；`/api/v1/users/** → mall-user`；`/api/v1/products/** → mall-product`；`/api/v1/orders/** → mall-order`。
- JWT 白名单：`/api/v1/auth/**`、`GET /api/v1/products/**`、所有 OPTIONS 预检请求。其余请求必须携带合法 Bearer Token。
- 鉴权通过后，网关将 `userId` 写入 `X-User-Id` 请求头传递给下游；**下游接口的用户身份一律取自 X-User-Id，不接受请求体/查询参数传入的 userId**。

### 3.7 前端请求约定

- axios baseURL：`/api`（开发环境由 vite proxy 代理 `/api` → `http://localhost:8080`；生产构建通过 `VITE_API_BASE` 环境变量注入）。注意：契约路径已含 `/api` 前缀，禁止出现 `/api/api` 双层前缀。
- axios 响应拦截器统一拆包 `Result<T>`：code≠0 时 reject 并携带 code/message；401 时清除本地 token 并跳转登录页。
- token 存储于 localStorage（键名 `mall_token`）。

---

## 4. 步骤 1：初始化项目结构

### 任务 A：后端骨架

生成 Maven 多模块项目：

```text
mall-consistency-lab/
├── pom.xml                        （父 POM，dependencyManagement 锁定第 2 节全部版本）
├── mall-gateway/
│   ├── pom.xml
│   └── src/main/java/com/mall/gateway/GatewayApplication.java
│   └── src/main/resources/application.yml
├── mall-user/
│   ├── pom.xml
│   └── src/main/java/com/mall/user/UserServiceApplication.java
│   └── src/main/resources/application.yml
├── mall-product/
│   ├── pom.xml
│   └── src/main/java/com/mall/product/ProductServiceApplication.java
│   └── src/main/resources/application.yml
├── mall-order/
│   ├── pom.xml
│   └── src/main/java/com/mall/order/OrderServiceApplication.java
│   └── src/main/resources/application.yml
└── mall-common/
    ├── pom.xml
    ├── src/main/java/com/mall/common/result/Result.java
    ├── src/main/java/com/mall/common/result/ResultCode.java      （含 INSUFFICIENT_STOCK、UNAUTHORIZED 等错误码）
    ├── src/main/java/com/mall/common/exception/BizException.java
    ├── src/main/java/com/mall/common/exception/GlobalExceptionHandler.java
    └── src/main/java/com/mall/common/util/JwtUtil.java            （供 gateway 与 user 共用）
```

要求：
- 每个服务的 application.yml：服务名、端口、Nacos 注册中心地址 `${NACOS_ADDR:localhost:8848}`、数据库连接（`${DB_URL:...}` 等环境变量 + 默认值）。
- mall-gateway 的 application.yml 包含 3.6 节全部路由规则（此时过滤器尚未实现，路由先行）。
- JwtUtil 基于 jjwt 0.12.5，HS256，密钥来自环境变量 `JWT_SECRET`（不少于 32 字节），提供 `generateToken(userId, username)` 与 `parseToken(token)` 两个静态方法。

### 任务 B：前端骨架

生成 pnpm monorepo：

```text
mall-consistency-lab-frontend/
├── package.json
├── pnpm-workspace.yaml               （packages: packages/*, shared）
├── packages/
│   ├── vue3-admin/
│   │   ├── package.json / vite.config.ts / tsconfig.json / index.html
│   │   └── src/{main.ts, App.vue, router/index.ts, api/request.ts, api/, views/, components/}
│   └── vue3-mall/
│       ├── package.json / vite.config.ts / tsconfig.json / index.html
│       └── src/{main.ts, App.vue, router/index.ts, api/request.ts, api/, views/, components/}
└── shared/
    ├── package.json                   （name: @mall/shared）
    └── src/types/index.ts
```

要求：
- vue3-admin 使用 Element Plus，vue3-mall 使用 Vant 4；均使用 vue-router、pinia、axios。
- 按 3.7 节实现 axios 实例与拦截器；两个 vite.config.ts 配置 server.port（3000/3001）与 `/api` 代理。
- 各放一个占位首页（AdminHome.vue / MallHome.vue）并注册路由，保证骨架可构建通过。

### 任务 C：主代理

生成 `docs/project-structure.md`：每个模块职责、端口、依赖关系、启动顺序。

### Gate 1

- 后端：`mvn -q -DskipTests compile` 全部模块通过。
- 前端：`pnpm install` 成功后 `pnpm -r build` 通过（或至少 `vue-tsc --noEmit` 通过）。
- 环境缺少工具时按 0.2 处理。

---

## 5. 步骤 2：定义核心契约（订单 + 商品）

### 任务 A：契约文件（先行）

输出 `docs/api-contracts/order-api.yaml` 与 `docs/api-contracts/product-api.yaml`（OpenAPI 3.0.3，version 均为 1.0.0）。

**order-api.yaml 接口定义**：

| 接口 | 说明 |
|---|---|
| `POST /api/v1/orders` | 创建订单（需鉴权）。请求体仅含 `productId(int64)`、`count(int32, min:1)`；**userId 不得出现在请求体**（取自 X-User-Id）。201 → `Result<OrderDTO>`；409 → code=INSUFFICIENT_STOCK；401 未授权 |
| `GET /api/v1/orders/{orderNo}` | 查询订单详情（需鉴权，仅可查本人订单） |
| `GET /api/v1/orders?page=&size=` | 分页查询当前用户订单（需鉴权） |
| `POST /api/v1/orders/{orderNo}/pay` | 模拟支付（需鉴权）。仅 PENDING 可支付，否则 409；成功返回更新后的 OrderDTO |

schemas：`OrderDTO { orderNo(string), status(enum: PENDING/PAID/SHIPPED/COMPLETED), totalAmount(number, format: decimal), createdAt(string, date-time) }`、`CreateOrderRequest`、`PageResult { list, total, page, size }`、`Result`、`ErrorResponse`。

**product-api.yaml 接口定义**：

| 接口 | 说明 |
|---|---|
| `GET /api/v1/products?page=&size=&categoryId=&keyword=` | 分页 + 分类筛选 + 关键词搜索（公开） |
| `GET /api/v1/products/{productId}` | 商品详情（公开） |
| `POST /api/v1/products` / `PUT /api/v1/products/{productId}` / `DELETE /api/v1/products/{productId}` | B 端增删改（本期不做角色校验） |

schemas：`ProductDTO { id, name, description, price(decimal), stock(int), categoryId, imageUrl, status(enum: ON_SALE/OFF_SALE), createdAt }`、`ProductCreateRequest`、`PageResult`、`Result`。

### 任务 B：C 端订单确认页（依赖任务 A，读取两个契约后执行）

输出：
- `packages/vue3-mall/src/views/OrderConfirm.vue`
- `packages/vue3-mall/src/api/order.ts`
- `shared/src/types/order.ts`、`shared/src/types/product.ts`（手写，与契约逐字段对齐）

要求：
- 展示商品信息（名称、价格、图片，数据来自 product 契约的详情接口类型）、数量选择器（1 ~ 库存）、收货地址选择（静态地址数组）、提交订单按钮。
- 提交调用 `POST /api/v1/orders`；409 提示「库存不足」，401 跳转登录页。
- 严格 TypeScript，类型一律从 `@mall/shared` 导入。

### Gate 2

- 每个 YAML 可被解析（可用 `python -c "import yaml,sys;yaml.safe_load(open(sys.argv[1]))" <file>` 或等价 node 方式）；无解析工具时标注未自检。
- 抽查必备字段：`openapi: 3.0.3`、`info.version`、全部 paths 与 components/schemas 齐全。

---

## 6. 步骤 3：并行开发业务模块（互不依赖，可串行）

### Agent-1：用户鉴权

文件：
- `mall-gateway/src/main/java/com/mall/gateway/filter/JwtAuthGlobalFilter.java`
- `mall-user/src/main/java/com/mall/user/controller/AuthController.java`
- `docs/api-contracts/auth-api.yaml`（v1.0.0）

要求：
- JwtAuthGlobalFilter 实现 `GlobalFilter, Ordered`：按 3.6 节白名单放行；从 `Authorization: Bearer <token>` 提取 JWT，用 mall-common 的 JwtUtil 验签；失败返回 401（Result 格式 JSON）；成功将 userId 写入 `X-User-Id` 传给下游；**必须拦截外部对 `/internal/**` 的访问**。
- AuthController：`POST /api/v1/auth/register {username,password}`、`POST /api/v1/auth/login → {token, userId, username}`、`GET /api/v1/auth/me`。密码使用 BCrypt（spring-security-crypto 即可，不引入完整 Spring Security）。
- auth-api.yaml 与上述接口一致。

### Agent-2：商品 CRUD（实现步骤 2 已产出的 product-api.yaml）

文件：ProductController / ProductService / ProductServiceImpl / ProductMapper（MyBatis-Plus）/ entity/Product / dto/ProductDTO，路径按 3.1 节包名。

要求：
- MyBatis-Plus 实现分页、分类筛选、关键词搜索；乐观锁字段 `version`（`@Version`）。
- `decreaseStock(productId, count, orderNo)`：乐观锁扣减 + 失败重试（重试次数【自主决策】），并实现 3.5 节幂等语义（幂等记录方式【自主决策】：独立表 / Redis / 其他均可，记录理由）。
- 商品详情接入 Redis 缓存（3.4 节约定）。
- **若实现中发现契约字段不合理，允许修改契约**：patch 版本号（1.0.0→1.0.1），并在报告中显著声明变更点，由主流程同步前端类型。

### Agent-3：B 端商品列表页

文件：`packages/vue3-admin/src/views/product/ProductList.vue`、`packages/vue3-admin/src/api/product.ts`。

要求：Element Plus `el-table` 展示；分页、关键词搜索、分类筛选；新增/编辑弹窗表单（含必填校验）；删除二次确认（`ElMessageBox.confirm`）。类型从 `@mall/shared` 导入。

### Agent-4：最小单元测试（加分项，但 CI 依赖它）

文件：
- `mall-order/src/test/java/com/mall/order/service/OrderServiceTest.java`（Mockito mock Feign 客户端与 Mapper，覆盖：正常下单、库存不足抛 BizException 两个用例）
- `mall-common/src/test/java/com/mall/common/util/JwtUtilTest.java`（签发后能解析、篡改后解析失败）

### Gate 3

- 后端 `mvn -q -DskipTests compile` 通过；`mvn -q test` 通过（环境无 Maven 则标注未自检）。
- 前端 `pnpm -r build` 通过。

---

## 7. 步骤 4：数据库设计

输出：`docs/sql/user-schema.sql`、`docs/sql/product-schema.sql`、`docs/sql/order-schema.sql`。

通用要求：InnoDB、utf8mb4、所有表含 `created_at`/`updated_at`、必要索引、每个文件末尾含测试数据 INSERT。

| 文件 | 表 | 关键约束 |
|---|---|---|
| user-schema | `user`（id, username, password_hash, phone, email, avatar, status） | username 唯一索引、phone 普通索引 |
| product-schema | `category`（id, name）、`product`（id, name, description, price DECIMAL(10,2), stock, category_id, image_url, version, status） | product(category_id)、product(status) 索引 |
| order-schema | `order`（id, order_no, user_id, total_amount DECIMAL(10,2), status, address_id）、`order_item`（id, order_id, product_id, product_name, price DECIMAL(10,2), count） | order_no 唯一索引、order(user_id, created_at)、order_item(order_id) 索引 |

**种子数据跨库一致性（强制）**：
- `mall_user.user` 必须含 id=1 用户；password_hash 为 '123456' 的 BCrypt 值——若可离线生成则写入真实值，否则写占位字符串并注释说明「种子用户不可登录，验收请使用注册接口」。
- `mall_product`：category id=1（数码）、id=2（服饰）；product id=1（stock=100，category_id=1）、product id=2（stock=5，用于触发库存不足 409 测试）。
- `mall_order`：为 user_id=1 预置 1~2 条订单（status=PAID，order_item 引用 product_id=1）。

### Gate 4

- 语句完整、以分号结尾、建表均声明 ENGINE 与字符集；有 MySQL 环境时可 `mysql --help` 之外不做实跑，语法自查即可。

---

## 8. 步骤 5：基础设施与部署

### 8.1 Dockerfile（每个服务一份，放在各模块根目录）

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE {对应端口}
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 8.2 docker-compose.yml

编排：MySQL 8.0（初始化三库，挂载 docs/sql 到 /docker-entrypoint-initdb.d）、Redis 7、Nacos 2.3 单机模式、四个业务服务。

- 健康检查：MySQL 用 `mysqladmin ping`；Redis 用 `redis-cli ping`；Nacos 用 `curl -f http://localhost:8848/nacos/`（镜像需确认含 curl，否则换 wget 写法）。
- 业务服务 `depends_on` 使用 `condition: service_healthy`；Nacos 健康后业务才可启动。
- 业务服务环境变量：`NACOS_ADDR=nacos:8848`、各自的 DB_URL/DB 账号密码、`JWT_SECRET`、`REDIS_HOST=redis`；**compose 中的密钥值加注释「仅本地演示用」**。

### 8.3 .github/workflows/ci.yml

- 触发：push to main、pull_request。
- 后端 job：checkout → setup-jdk 17（含 maven 缓存）→ `mvn -B compile` → `mvn -B test`。
- 前端 job：checkout → pnpm/action-setup（v9）→ setup-node 20（cache: pnpm）→ `pnpm install --frozen-lockfile` → `pnpm -r build`。
- 镜像构建 job：为四个服务执行 `docker build`（仅构建验证，不 push）。

### 8.4 Makefile

`make up`（docker compose up -d）、`make down`、`make build`（mvn clean package -DskipTests）、`make test`（mvn test）、`make logs`（docker compose logs -f --tail=100）。

### Gate 5

- `docker compose config` 解析通过（无 Docker 则标注未自检）；ci.yml 为合法 YAML。

---

## 9. 步骤 6：契约校验与文档

1. 逐一校验 `docs/api-contracts/*.yaml`：可解析、含 openapi/info.version/paths/schemas；与对应后端 Controller、前端 TS 类型做**字段级交叉核对**（名称、类型、必填性），不一致处以契约为准修正代码，并在 DECISIONS.md 记录。
2. 生成 `docs/api-contracts/README.md`：契约清单、版本号、变更历史、Swagger UI 加载方式。
3. 生成根目录 `README.md`：项目简介、技术栈、Mermaid 架构图（含网关/三服务/Nacos/MySQL/Redis/两个前端）、快速启动指南（本地启动与 docker compose 两种）、目录结构说明、已声明的简化边界（照抄 3.4 节）。

### Gate 6

- 交叉核对清单全部打勾或记录差异处理结果；README 含全部要求章节。

---

## 10. 步骤 7：全局验收自检

输出一份 `docs/ACCEPTANCE.md`，包含：

1. **编译总检**：后端 `mvn -q -DskipTests package`、前端 `pnpm -r build` 的最终结果。
2. **人工验收走查清单**（供评审人逐条点开验证，写明每一步的请求/页面路径与预期结果，验收标准详见 PRD 第 4 节）：
   - 注册新用户 → 登录拿到 token
   - C 端商品列表 → 详情 → 订单确认页 → 提交订单成功（product_id=1）
   - 对 product_id=2（stock=5）下单 count=6 → 预期 409 库存不足提示
   - 不带 token 请求订单接口 → 预期 401 且前端跳登录
   - 模拟支付 → 订单状态 PENDING→PAID；订单列表分页可见
   - B 端商品列表：搜索、分页、分类筛选、新增、编辑、删除（含二次确认）
   - docker compose（如有环境）：`make up` 后全部容器 healthy，网关可访问
3. **已知限制清单**：汇总 DECISIONS.md 中所有折中项与未自检项。

### Gate 7

- ACCEPTANCE.md 覆盖上述全部条目；任何一条不满足必须在「已知限制」中显式列出，禁止静默遗漏。

---

## 11. 【自主决策】项汇总

以下条目本指令**故意不给出答案**，由你自行选型并在 DECISIONS.md 记录完整理由：

1. 库存扣减失败时的重试策略与一致性保障方式（乐观锁重试参数、是否引入其他机制）。
2. 库存扣减幂等记录的落地方式（独立表 / Redis / 其他）。
3. `JWT_SECRET` 与各服务敏感配置的注入方式细节（环境变量 / Nacos 配置的取舍与组合）。
4. Feign 调用 product-service 失败时的处理策略（重试 / 降级 / 直接报错）。
