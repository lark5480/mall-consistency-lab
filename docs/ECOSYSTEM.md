# 生态调研与定位

> **性质说明**：本文档是**完工后的回溯性调研**（2026-09，项目代码与两轮 Code Review 完成之后），不是开工前的选型依据——项目最初只是"顺手做一个电商练手"的想法，没有做过生态调查。补做它的目的有三：① 给出与 GitHub 现存项目的差异化声明；② 用现存项目的公开实现**交叉验证**本项目的关键设计；③ 沉淀方法论，供下一个项目在开工前真正执行（文档先行）。
>
> **方法**：exa 搜索概览定位与热度 → DeepWiki 精读头部项目源码级实现（订单/库存一致性路径）→ 与本项目 `DECISIONS.md` / `CONSISTENCY.md` 逐点对照。

---

## 一、广度型 mall 项目盘点（截至 2026-09）

| 项目 | Stars | 定位 | 跨服务/库存一致性方案 |
|---|---|---|---|
| [macrozheng/mall](https://github.com/macrozheng/mall) | 84.6k | Spring Boot **单体**全家桶：前台商城+后台管理，功能最全的练手教材 | 单库**本地事务** + `lock_stock` 预占模式：下单原子 UPDATE 加锁定库存 → 支付成功才真扣；RabbitMQ 延迟消息关单。**无跨服务问题，也无 Seata** |
| [YunaiV/yudao-cloud](https://github.com/YunaiV/yudao-cloud) | 19.4k | Spring Cloud Alibaba **企业级脚手架**：商城/CRM/ERP/MES 等 20+ 模块，代码生成器 | **Seata 1.6.1 框架托管**；库存走独立 WMS 服务，`@Transactional` 本地原子 + 全量库存流水表 + `checkInventory` 对账接口 |
| [macrozheng/mall-swarm](https://github.com/macrozheng/mall-swarm) | 13.0k | mall 的微服务版：K8s/ES/监控中心齐全 | 技术栈**列有 Seata**（框架黑盒路线）；业务功能与 mall 单体同源 |
| [newbee-ltd/newbee-mall](https://github.com/newbee-ltd/newbee-mall)（家族） | 11.5k | 新手友好 Spring Boot 商城（GPL-3.0，**注意传染性协议，勿拷代码**） | 微服务版 [newbee-mall-cloud](https://github.com/newbee-ltd/newbee-mall-cloud)（仅 354 star）集成 Seata 1.4.2 |
| [macrozheng/mall-learning](https://github.com/macrozheng/mall-learning) | 13.4k | mall 的配套**教程仓** | —（本项目原名撞了它，已改名 mall-consistency-lab，避嫌正确） |

**洞察**：广度型项目的跨服务一致性只有两条路线——

1. **单体化回避**（mall）：库存和订单同库，本地事务天然一致，"锁库存"字段解决超卖即可，根本不产生分布式事务问题；
2. **框架黑盒**（yudao-cloud / mall-swarm / newbee-mall-cloud）：引 Seata，AT 模式自动 undo/回滚，使用者不需要理解补偿与对账。

**没有任何一个广度型项目手写过"幂等扣减 → 同步补偿 → 定时对账"的完整闭环**——因为对教学/脚手架项目来说，要么回避、要么托管，手写闭环性价比低。这正好是本项目的生存空间。

## 二、一致性细分赛道参照

| 参照 | 形态 | 与本项目的差异 |
|---|---|---|
| [eventuate-tram-sagas](https://github.com/eventuate-tram/eventuate-tram-sagas)（Chris Richardson《微服务架构设计模式》作者） | **编排式 Saga 框架** + 事务性消息（MESSAGE 表 + binlog CDC 投递 Kafka），配套 customers-and-orders 经典示例与 [ftgo-application](https://github.com/microservice-patterns/ftgo-application) | 异步消息驱动，需要额外 CDC 中间件；本项目是**同步 Feign 调用**下的 Saga，不引入 MQ（取舍见 DECISIONS：下单需同步确认实时库存） |
| [Chorus](https://github.com/mohamedadel96e/Chorus)（个人项目） | 编排式 Saga + 事务性 Outbox + 幂等消费者 + 混沌测试脚本，多语言四服务 | **同类定位的直接印证**："单点深挖一致性 + 用测试脚本证明恢复能力 + ADR 记录取舍"这个赛道成立；差异在于它是事件驱动、无完整 B/C 前端，本项目同步链路 + 全栈 + 契约驱动 + CI |

## 三、交叉验证：本项目的关键设计在别处的印证

| 本项目设计 | 生态印证 |
|---|---|
| `stock_dedup_log` 去重表唯一键仲裁（先插占位再扣减） | Chorus ADR-003 的 `processed_events` 表（唯一约束 + 同事务更新）——同一模式在消息驱动场景的等价物，"DB 唯一键是并发唯一性的可靠裁判"得到独立验证 |
| 孤儿扣减对账任务（按订单状态回补） | yudao-cloud WMS 的 `checkInventory` 库存核对接口——生产级脚手架同样认为"对账兜底"必要，只是本项目做成了自动定时任务 |
| 超卖防护：乐观锁条件更新 | mall 的 `lock_stock` 原子 UPDATE（CASE 表达式）——本质同源：都靠单条条件 UPDATE 的原子性裁决并发，而非应用层锁 |
| 不用 Seata（为单一跨服务写操作引入框架不成比例） | mall 单体直接回避跨服务；广度型项目里真正落 Seata 的 newbee-mall-cloud 仅 354 star，社区用脚投票的结果与 DECISIONS 的判断一致 |
| 补偿路径显式建模（restoreStock 幂等回补） | 两条路线的共同短板恰恰是补偿：Seata AT 自动补偿对使用者不可见，mall 预占模式把"取消释放"做成了必经路径。本项目选择**可解释的显式补偿 + 对账兜底**，处于两者之间且有测试回归防线 |

**与 mall 预占模式的正面对照**（面试高频点）：mall「下单锁库存 → 支付才真扣」，好处是未支付窗口不占真实库存、取消只释放锁；代价是每个 SKU 多一个锁定态字段、真扣发生在支付回调。本项目「下单即扣 + 失败补偿 + 对账兜底」，好处是链路短、补偿路径被并发集成测试覆盖；代价是取消依赖回补的正确性（已有 CAS + 幂等兜底）。**两者都对，取舍点在于是否愿意为"链路可解释"承担补偿路径的实现复杂度**——这正是简历项目要展示的判断力。

## 四、差异化声明

> 有了 84.6k star 的 mall，为什么还要这个项目？

广度型项目回答"**电商系统长什么样**"，本项目回答"**跨服务数据每一步为什么不会乱**"。具体差异：

- **深度 vs 广度**：不做购物车/优惠券/促销/搜索，只把「下单跨服务扣库存」这一条链路的一致性做到每个分支可解释、可测试、可复盘（40+ 条 DECISIONS、两轮 Review、并发集成测试直接复现两个 P0 bug）；
- **手写 vs 黑盒**：不用 Seata/MQ/其他框架遮蔽问题，六道防线逐条对应代码行（CONSISTENCY.md），每一层的失效场景都有名字；
- **闭环 vs 演示**：补偿和对账不是文档里的口头承诺——对账任务有独立的孤儿回补测试，补偿有 8 线程并发幂等测试，CI 真实执行。

## 五、从生态吸收的 Roadmap

按"是否强化定位"筛选，而非照抄功能清单：

| 优先级 | 事项 | 来源与理由 |
|---|---|---|
| P0 | **混沌测试脚本**：在 Saga 中途 kill order-service（如扣减成功后、落单前），重启后验证对账任务自动回补、库存最终一致 | 印证自 Chorus 的 chaos test；直接补掉 ACCEPTANCE 已知限制"极端进程崩溃窗口靠 ERROR 日志暴露"——从"承认边界"变成"证明边界可恢复"，是定位内最强的增强 |
| P1 | **PENDING 超时自动关单**：定时扫描（沿用现有 auto-complete 任务的模式，不为此引入 MQ） | 印证自 mall 的延迟关单；补掉 CONSISTENCY.md 已声明的已知边界，且与现有"定时任务不加分布式锁"决策兼容 |
| P2 | 秒杀场景（Redis 预扣 + 限流） | mall/newbee-mall-plus 都有；但 DECISIONS 已明确指向作者另一个项目 flash-sale，**不在本仓库做**，避免稀释定位 |
| 不做 | 购物车/优惠券/ES 搜索/多租户/代码生成器 | 广度型赛道的事，做了只会让"深度"的声明变假 |

## 六、方法论沉淀（写给下一个项目）

1. 本简报（exa 概览 + DeepWiki 精读 + README/源码核验）约 30 分钟即可完成，**开工前做的成本远低于完工后补做**；下一个项目把它作为步骤 0。
2. 调研产出物不是"竞品功能对比大表格"，而是三件事：**差异化声明**（一句话说清凭什么共存）、**交叉验证**（自己的设计有没有人证）、**roadmap 过滤器**（只吸收强化定位的东西）。
3. 诚实标注文档时序比伪装流程更重要：git 历史无法伪造，"回溯性调研"本身也是合理文档品类，伪装不是。
