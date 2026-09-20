# Stage 4-B-2: 订单领域核心模型与状态机实现总结报告

- **执行阶段**：Stage 4-B-2 (Domain Model, State Machine & Core Order Service)
- **涵盖组件**：`OrderStatus`, `TradeOrder`, `TradeOrderMapper`, `OrderStateMachine`, `OrderService`, `OrderServiceImpl`, `CampusTradeStage4B2Tests`
- **数据表映射**：`campus_trade.trade_order` (MyBatis-Plus schema 统一托管)

---

## 一、新增与变更文件清单

1. **[NEW]** [`backend/src/main/java/com/campustrade/enums/OrderStatus.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/enums/OrderStatus.java)
   - 订单核心状态枚举：`WAIT_SELLER_CONFIRM` (待卖家确认), `WAIT_MEET` (待面交), `COMPLETED` (已完成), `CANCELLED` (已取消)；
   - 标注 `@EnumValue` 与 `@JsonValue`，确保 MyBatis-Plus 与 JSON 序列化严格统一，杜绝业务层直接使用魔法字符串。
2. **[NEW]** [`backend/src/main/java/com/campustrade/entity/TradeOrder.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/entity/TradeOrder.java)
   - 完整映射 V4 `trade_order` 表所有 20 个字段，采用 `@TableName("trade_order")`，避免 schema 重复前缀；
   - 包含商品标题/价格/主图三大快照字段，面交地点与留言，以及全生命周期时序时间戳。
3. **[NEW]** [`backend/src/main/java/com/campustrade/mapper/TradeOrderMapper.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/mapper/TradeOrderMapper.java)
   - 继承 MyBatis-Plus `BaseMapper<TradeOrder>`。
4. **[NEW]** [`backend/src/main/java/com/campustrade/service/order/OrderStateMachine.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/order/OrderStateMachine.java)
   - 状态机核心组件：严格定义合法流转白名单与终态不可逆约束；
   - 提供 `canTransition(OrderStatus from, OrderStatus to)` 与 `validateTransition(OrderStatus from, OrderStatus to)`。
5. **[NEW]** [`backend/src/main/java/com/campustrade/dto/CreateOrderDTO.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/dto/CreateOrderDTO.java)
   - 创建订单入参 DTO，涵盖 `goodsId`, `meetLocation`, `buyerMessage` 及 JSR-303 参数校验。
6. **[NEW]** [`backend/src/main/java/com/campustrade/exception/OrderBusinessException.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/exception/OrderBusinessException.java)
   - 订单业务专用异常，继承 `BusinessException`，与项目统一错误码规范契合。
7. **[NEW]** [`backend/src/main/java/com/campustrade/service/OrderService.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/OrderService.java)
   - 订单业务接口，定义 `createOrder`, `confirmOrder`, `cancelOrder`, `completeOrder`, `getOrderById`, `getOrderByOrderNo`。
8. **[NEW]** [`backend/src/main/java/com/campustrade/service/impl/OrderServiceImpl.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/impl/OrderServiceImpl.java)
   - 订单业务实现类：
     - 细粒度校验（买家非卖家、商品存在且在售、取消原因非空、操作人权限校验）；
     - 快照保存（商品标题、价格、主图封面）；
     - 状态机流转控制；
     - 事务一致性（`@Transactional` 管理订单插入/状态更新 + 商品状态联动 `ON_SALE` / `LOCKED` / `SOLD` + 双方 `UserCredit.trade_count` 自动累加）；
     - 业务订单号生成算法 (`ORDyyyyMMddHHmmssXXXX`)。
9. **[MODIFIED]** [`backend/src/main/resources/application.yml`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/resources/application.yml)
   - 补充 `mybatis-plus.type-enums-package: com.campustrade.enums`，让持久层自动识别枚举映射。
10. **[NEW]** [`backend/src/test/java/com/campustrade/CampusTradeStage4B2Tests.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/test/java/com/campustrade/CampusTradeStage4B2Tests.java)
    - 包含 11 个自动化集成测试用例，覆盖全部 9 项指标及边界拓展。

---

## 二、状态机设计与流转矩阵

```
[买家下单] -> WAIT_SELLER_CONFIRM (待卖家确认)
                    │
                    ├──[卖家确认]──────> WAIT_MEET (待面交)
                    │                       │
                    │                       ├──[双方验货交割]──> COMPLETED (已完成, 终态)
                    │                       │
                    └──[买家/卖家取消]──────┴──[买家/卖家取消]──> CANCELLED (已取消, 终态)
```

### 合法流转
- `WAIT_SELLER_CONFIRM -> WAIT_MEET` (卖家确认接单)
- `WAIT_SELLER_CONFIRM -> CANCELLED` (买家或卖家取消)
- `WAIT_MEET -> COMPLETED` (买家或卖家确认完成面交)
- `WAIT_MEET -> CANCELLED` (面交未达成或协商取消)

### 严格禁止
- `WAIT_SELLER_CONFIRM -> COMPLETED` (未确认接单不能直接完成)
- `COMPLETED -> *` (已完成订单不可逆)
- `CANCELLED -> *` (已取消订单不可逆)
- 任何自流转 (`STATE -> SAME_STATE`)
- `WAIT_MEET -> WAIT_SELLER_CONFIRM` (不可回退状态)

---

## 三、事务与多表联动设计

1. **创建订单 (`createOrder`)**：
   - 校验：买家不能买自己商品、商品必须为 `ON_SALE`；
   - 写入：`trade_order` 生成初始状态 `WAIT_SELLER_CONFIRM`，固化快照（标题、价格、主图 URL）；
   - 联动：`goods.status` 变更为 `LOCKED`（锁定防止二次下单）。
2. **确认接单 (`confirmOrder`)**：
   - 校验：操作人必须为卖家；
   - 状态机：`WAIT_SELLER_CONFIRM -> WAIT_MEET`；
   - 写入：记录 `confirmed_time`。
3. **取消订单 (`cancelOrder`)**：
   - 校验：操作人必须为买家或卖家，取消原因非空；
   - 状态机：`WAIT_SELLER_CONFIRM / WAIT_MEET -> CANCELLED`；
   - 写入：记录 `cancelled_by`, `cancel_reason`, `cancelled_time`；
   - 联动：若关联商品为 `LOCKED`，自动恢复为 `ON_SALE`。
4. **完成订单 (`completeOrder`)**：
   - 校验：操作人必须为买家或卖家；
   - 状态机：`WAIT_MEET -> COMPLETED`；
   - 写入：记录 `completed_time`；
   - 联动：`goods.status` 变更为 `SOLD`；
   - 信用结算：买家 `trade_count + 1`，卖家 `trade_count + 1`（自动防御性初始化 `UserCredit`）。

---

## 四、自动化测试验证结果

### 1. Stage 4-B-2 专项测试 (`CampusTradeStage4B2Tests`)
11 个测试用例 100% 通过：
1. `test01_buyer_cannot_buy_own_goods`: 买家不能购买自己发布的商品 -> 抛出 400 业务异常
2. `test02_non_on_sale_goods_cannot_be_ordered`: 商品处于 LOCKED/SOLD/OFF_SHELF 状态时拦截下单
3. `test03_create_order_success_and_snapshots_verified`: 创建订单成功，goods 变 LOCKED，order 为 WAIT_SELLER_CONFIRM，快照字段正确
4. `test04_non_seller_cannot_confirm_order`: 买家与非当事第三方无法确认订单 -> 抛出 403
5. `test05_confirm_order_success`: 卖家成功确认接单，order 变 WAIT_MEET，记录 confirmedTime
6. `test06_third_party_cannot_cancel_order`: 非买家非卖家无法取消订单 -> 抛出 403
7. `test07_cancel_order_success_and_goods_status_restored`: 成功取消订单，order 变 CANCELLED，goods 恢复 ON_SALE
8. `test08_complete_order_success_and_trade_count_incremented`: 成功完成订单，order 变 COMPLETED，goods 变 SOLD，买卖双方 trade_count 均 + 1
9. `test09_invalid_state_transition_prevented`: 待卖家确认直接完成被状态机拦截
10. `test10_state_machine_transition_matrix`: 状态机全状态流转矩阵单元验证与终态不可逆约束
11. `test11_cancel_order_reason_not_empty`: 取消订单时空原因校验拦截

### 2. 全量测试回归 (`mvn test`)
- **Total Tests Run**: **114**
- **Failures**: **0**
- **Errors**: **0**
- **Skipped**: **0**
- **Status**: **BUILD SUCCESS**
- **覆盖版本**：Stage 0 ~ Stage 3.5 全量测试 + Stage 4-B-1 (4 个) + Stage 4-B-2 (11 个) 无缝全绿运行。
