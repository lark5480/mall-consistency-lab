# 验收自检报告

## 编译总检

- 后端：`mvn -q -DskipTests package` 通过（本机 JDK 21 编译，Maven `release=17` 锁定字节码目标）。
- 后端测试：`mvn -B test` 通过，0 失败（2026-09-02 复跑：order 模块 19 例 + 各模块合计；Testcontainers 集成测试在真实 Docker 环境执行）。
- 前端：`pnpm -r build` 通过，两个应用均执行 `vue-tsc --noEmit && vite build`。
- 契约：auth(1.1.0)/user(1.0.0)/product(1.1.0)/order(1.2.0)/admin(1.0.0) 五个 YAML 可被 js-yaml 解析。
- Compose：`docker compose config --quiet` 通过。

## 真实 Docker 栈验收（2026-09-02，API 级走查）

完整栈 `docker compose up -d --build` 真实启动并全部 healthy（mysql/redis/nacos/gateway/user/product/order 七容器），按下方走查清单以 curl 实测：

- 登录/角色：demo(USER)/admin(ADMIN) 均可登录，JWT 含 role claim。
- 下单链路：product 1×2 下单成功返回 PENDING，收货人快照（张三/电话/地址）固化进订单响应与 `mall_order.order` 表（utf8mb4 数据完整）；DB 确认 product 1 stock 100→98。
- 库存不足：product 2×6 返回 HTTP 409 / code 40001。
- 未登录：无 token 下单返回 401；外部访问 `/internal/**` 网关返回 404（不路由）。
- 鉴权边界：USER token 调 `/api/v1/admin/**` 返回 403(40301)；ADMIN token 正常。
- 取消回补：product 2 下单 2 件（stock 5→3）→ 取消成功（stock 3→5，乐观锁 version 1→2）；重复取消返回 409(40002)。
- 发货与看板：admin 发货 PAID→SHIPPED；`/api/v1/admin/stats/orders` 看板 GMV=5998(本次)+2999(种子)=8997，已取消订单正确不计入。
- 说明：走查经网关 `localhost:8080` 执行；本机 6379 被其他 Redis 占用，compose 已支持 `REDIS_HOST_PORT` 覆盖宿主机映射（容器内仍 6379）。B/C 前端页面走查（清单 1-3、10-12、16 的 UI 部分）需浏览器人工执行。

## 混沌测试（2026-09-03，P0 Roadmap 兑现）

`bash scripts/chaos-test.sh 3 12`（栈以 `RECONCILE_STALE_MINUTES=1 RECONCILE_INTERVAL_MS=15000` 加速对账启动）：

- 故障注入：36 个下单请求（随机支付/取消混合）的执行期间，3 轮随机时机 `docker kill` order-service 并自动拉起（每轮宕机约 15-20 秒，落点覆盖下单高峰）。
- 实测：第 1 轮 kill 砸中流量高峰（12 请求仅 2 单确认），10 个「已扣库存、订单未落库」的孤儿扣减全部由对账任务自动回补。
- 不变式全过：**I1 库存台账**（stock 变化 −16 == −ΔDEDUCTED 合计，跨杀服务/回补/对账三种路径守恒）；**I2 无孤儿扣减**（全表无 DEDUCTED 行对应缺失或已取消订单）；**I3 下单闭环**（14 个确认订单全部有去重行且状态一致）。
- 结论：「极端进程崩溃窗口靠 ERROR 日志暴露」的已知限制升级为「崩溃后由对账任务收敛，且有脚本可重复证明」。脚本手动执行，不进 CI 阻塞链（杀容器测试在共享 runner 上不稳定）。

## 人工走查清单

1. 注册新用户
   打开 `http://localhost:3001/login`，输入未使用的用户名和至少 6 位密码，点击「注册」。预期保存 token 并跳转首页；重复注册同名账号返回 HTTP 409「用户名已存在」。
2. 登录获取 token 与角色
   C 端使用 `demo / 123456` 登录（普通 USER）；B 端 `http://localhost:3000/login` 使用 `admin / admin123` 登录。预期 LocalStorage 出现 `mall_token` 与 `mall_user`（含 role 字段）。
3. 普通用户不能进后台
   用 demo 账号在 B 端登录页登录。预期提示「该账号不是管理员」，无法进入商品管理；直接访问 `http://localhost:3000/products` 被路由守卫弹回登录页。
4. ADMIN 管理商品
   admin 登录后进入 `http://localhost:3000/products`：关键词搜索、分类筛选、分页、新增、编辑均可用；删除出现二次确认弹窗。若手动构造普通用户 token 直接调用 `POST/PUT/DELETE /api/v1/products/**`，网关返回 HTTP 403。
5. C 端商品链路（含 addressId）
   打开 `http://localhost:3001/` → 商品 → 详情 → 立即购买 → 订单确认页，选择数量与静态收货地址后提交 product_id=1。预期下单成功并跳转「我的订单」；数据库 `mall_order.order.address_id` 等于所选地址 id。
6. 库存不足
   对 product_id=2 下单数量 6。预期 HTTP 409 / code 40001，前端提示「库存不足」（由订单服务翻译 product 的 409 响应体得到）。
7. 我的订单与模拟支付
   首页底部 Tabbar 进入「我的订单」。PENDING 订单显示待支付标签与「模拟支付」按钮，点击后变为已支付；分页加载正常，空账号未登录访问会跳转登录页。
8. 未登录保护
   清除 `mall_token` 后请求 `GET /api/v1/orders` 或进入需登录页面。预期网关返回 401 且 C 端跳转 `/login`；伪造 `X-User-Id` 请求头会被网关剥离，下游不会采信。
9. Compose 健康
   执行 `make up` 后检查 `docker compose ps`。预期 mysql、redis、nacos healthy，四个业务容器（含 gateway healthcheck）healthy；宿主机仅暴露 3306/6379/8848/9848/8080/3000/3001，业务服务 8081-8083 不再对外映射，直连 `localhost:8081` 应失败。

## v1.2 人工走查清单

10. C 端「我的」页
    demo 登录后底部 Tabbar 出现第三个「我的」入口。预期展示头像卡片（用户名/ID/角色）、注册时间；「编辑资料」修改手机号并保存后重新打开页面数值持久化。
11. 修改密码
    「我的」→「修改密码」：旧密码输错提示 409 文案且不生效；正确修改成功后退出登录，旧密码登录返回「用户名或密码错误」，新密码登录成功。
12. 地址簿
    「我的」→「收货地址」：demo 名下已有两条种子地址（张三-默认/李四）。新增、编辑、左滑删除、设默认均可用；设默认后其余地址的「默认」标记消失；首条地址删除后剩余地址中仍有默认项。
13. 真实地址下单
    商品详情 → 立即购买 → 订单确认页：地址栏展示真实默认地址；弹层可切换并可跳「管理地址」。下单成功后查 `mall_order.order`，receiver_name/receiver_phone/receiver_address 三列等于所选地址内容。
14. 取消订单与库存回补
    对 stock=5 的商品下单 2 件 →「我的订单」该订单出现「取消订单」按钮。确认取消后状态变已取消，商品详情 stock 恢复为 5；对同一订单重复取消或对已支付订单取消返回 409(40002)。
15. B 端订单管理
    admin 登录 `http://localhost:3000/orders`：全量订单分页展示，按状态筛选、按订单号搜索可用；PAID 订单点「发货」变 SHIPPED 且 C 端同步可见；「详情」抽屉展示收货人快照与商品明细行。
16. B 端看板与分类管理
    admin 进入 `/dashboard`：统计卡片与近 7 日趋势折线图渲染，数值与 DB 手工 count 对账一致（已取消订单不计入 GMV）。进入 `/categories`：新增/重命名分类生效；删除仍被商品引用的「数码」分类被拒绝并提示数量。
17. ADMIN 鉴权边界
    用 demo（USER）token 直接调用任意 `/api/v1/admin/**` 接口（如 GET /api/v1/admin/orders），网关返回 HTTP 403；B 端路由守卫同样拦截 USER 账号。

## 已知限制

- 本机 JDK 为 21、Node 为 24、pnpm 为 11.x，非 PRD 建议版本；CI 中仍声明 Node 20 / pnpm 9。pnpm 11 需在 `pnpm-workspace.yaml` 的 `allowBuilds` 中放行 vue-demi/esbuild 构建脚本（pnpm 9 无此拦截，CI 不受影响）。
- 旧 MySQL 数据卷（含改名前的 `mall-learning_mysql-data`）需执行一次 `docs/sql/zz-migration-v1.4.sql`（可重复执行）；改名后 compose 新建 `mall-consistency-lab_mysql-data` 全新卷，迁移脚本自动生效。
- 存量 JWT 无 role claim：旧 token 需重新登录才能获得 ADMIN 语义（守卫读取的 mall_user 也随登录刷新）。
- 跨服务库存一致性采用幂等扣减 + 状态查询 + 补偿恢复，不引入分布式事务框架；进程崩溃窗口由定时对账兜底收敛（2026-09-03 起有混沌测试脚本可重复验证，见上节）。
- 支付为模拟实现；发货仅流转订单状态，不填物流单号，无超时自动关单。
- v1.2 起收货地址为真实服务（mall_user.address 表），历史遗留订单的 address_id 仍指向旧静态数据属正常现象；新订单均含 receiver_* 快照列。
- 头像与商品图片均为 URL 字符串，不做文件上传/OSS。
- admin 构建存在 Vite chunk 大小警告，不影响功能与构建结果。
