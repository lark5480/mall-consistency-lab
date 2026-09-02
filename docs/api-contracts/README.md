# API 契约

| 契约 | 版本 | 说明 |
|---|---|---|
| `auth-api.yaml` | 1.1.0 | 注册、登录、当前用户（响应含 `role`） |
| `user-api.yaml` | 1.0.0 | 个人资料查看/修改、修改密码、收货地址簿 CRUD（需登录） |
| `product-api.yaml` | 1.1.0 | 商品公开查询与 B 端 CRUD（写接口需 ADMIN，由网关校验）、公开分类列表 |
| `order-api.yaml` | 1.2.0 | 创建/查询订单、模拟支付、取消订单（下单必填 `addressId`；订单含收货人快照与明细） |
| `admin-api.yaml` | 1.0.0 | B 端订单管理（全量查询/详情/发货）、运营统计、分类管理（全部需 ADMIN，网关强制 `/api/v1/admin/**`） |

所有响应均使用 `{code,message,data}` 包装，成功 `code=0`。订单对外只暴露 `orderNo`。

变更历史：
- 2026-08-24：建立 v1.0.0 初始契约。
- 2026-08-25：auth 升级 1.1.0 —— 注册/登录/me 响应新增 `role: USER|ADMIN`；JWT 写入 `role` claim。
- 2026-08-25：order 升级 1.1.0 —— `CreateOrderRequest` 新增必填 `addressId(min=1)`；`OrderDTO` 新增 `addressId`。
- 2026-08-25：新增内部接口（不入公共契约，仅供服务内网调用）：
  - `GET /internal/products/stock/status/{orderNo}` 查询扣减状态（NONE/DEDUCTED/RESTORED）
  - `POST /internal/products/{productId}/stock/restore` 幂等补偿恢复库存
- v1.2：user 新增 1.0.0 —— `GET/PUT /api/v1/users/me`、`PUT /api/v1/users/me/password`、`/api/v1/users/me/addresses` 地址簿 CRUD。
- v1.2：order 升级 1.2.0 —— 状态枚举增加 `CANCELLED`；`POST /api/v1/orders/{orderNo}/cancel` 取消订单（幂等回补库存）；`OrderDTO` 增加收货人快照字段（receiverName/receiverPhone/receiverAddress）与可选 `items` 明细。
- v1.2：product 升级 1.1.0 —— 公开分类列表 `GET /api/v1/categories`。
- v1.2：admin 新增 1.0.0 —— B 端订单全量分页/筛选、订单详情含 items、发货（PAID→SHIPPED）、订单/商品统计；分类管理 CRUD（删除被引用返回 409）。
- v1.2：内部接口新增（不入公共契约）：`GET /internal/users/{userId}/addresses/{addressId}` 订单服务取地址快照。
- v1.4：内部接口变更（不入公共契约）：订单侧 `GET /internal/orders/{orderNo}/exists` 升级为 `GET /internal/orders/{orderNo}/state`（返回 `exists` + `status`），供对账任务按订单状态判断回补（仅订单不存在或已 CANCELLED 才回补）。对外契约无变化。

可在 Swagger UI 中选择 “File > Import file” 分别导入 YAML；也可使用本地 Swagger Editor 加载。各契约均自包含，不依赖外部引用文件。
