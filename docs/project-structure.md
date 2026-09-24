# 项目结构

| 模块 | 职责 | 端口 | 依赖 |
|---|---|---:|---|
| mall-gateway | 路由、JWT 全局鉴权、屏蔽 `/internal/**` | 8080 | Nacos |
| mall-user | 注册、登录、当前用户信息 | 8081 | MySQL `mall_user`、Nacos |
| mall-product | 商品 CRUD、详情缓存、幂等扣库存、孤儿扣减对账任务 | 8082 | MySQL `mall_product`、Redis、Nacos |
| mall-order | 订单创建、查询、模拟支付、取消/发货/确认收货、超时自动关单与自动完成任务 | 8083 | MySQL `mall_order`、mall-product Feign、mall-user Feign、Nacos |
| vue3-admin | B 端商品管理后台 | 3000 | 网关 |
| vue3-mall | C 端商城与下单支付 | 3001 | 网关 |

启动顺序：
1. 启动 MySQL、Redis、Nacos。
2. 启动 mall-user、mall-product、mall-order。
3. 服务注册成功后启动 mall-gateway。
4. 启动前端开发服务。

`docker compose up -d --build` 会按健康检查自动编排上述依赖。
