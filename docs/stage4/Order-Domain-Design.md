# Stage 4-A: 订单领域模型设计 (Order Domain Design)

- **所属模块**：交易订单中心 (Trade Order Domain)
- **核心定位**：管理校园二手交易意向发起、双方协商确认、线下验货履约、终态归档及信用沉淀的全生命周期。

---

## 一、领域模型定位与设计原则

在 DDD (领域驱动设计) 体系下，交易订单是校园二手交易闭环的核心聚合根 (Aggregate Root)：

1. **线下校园自提/面交场景特化**：
   - 平台不介入资金托管与物流快递，交易本质是**“基于校园信用的意向锁定与线下交割确认”**；
   - 订单记录的核心是“谁”、“向谁”、“以什么价格和商品特征”、“约定在何处”进行交割。
2. **交易事实不可变性 (Snapshot Principle)**：
   - 订单一旦生成，即构成买卖双方在特定时间点达成的契约事实；
   - 商品后续的任何改价、改名、编辑、删图或下架，**绝对不能篡改订单内的历史事实**。

---

## 二、订单核心实体模型 (`Order`)

```
+---------------------------------------------------------------------------------+
|                                 Order (聚合根)                                   |
+---------------------------------------------------------------------------------+
| [基础标识]                                                                      |
| - id: Long                                (系统内部分布式主键)                   |
| - orderNo: String                         (业务订单号: ORD+时间戳+4位随机校验码)   |
|                                                                                 |
| [交易主体]                                                                      |
| - buyerId: Long                           (买家用户 ID)                          |
| - sellerId: Long                          (卖家用户 ID)                          |
| - schoolId: Long                          (交易发生的高校校区 ID)                |
| - goodsId: Long                           (关联商品 ID)                          |
|                                                                                 |
| [不可变商品快照 (Snapshot)]                                                      |
| - goodsTitleSnapshot: String              (下单瞬间的商品标题)                   |
| - goodsPriceSnapshot: BigDecimal          (下单瞬间的商品成交价格)               |
| - goodsImageSnapshot: String              (下单瞬间的商品主图 URL)               |
|                                                                                 |
| [履约与面交约定]                                                                |
| - meetLocation: String                    (约定的校园面交地点，如：学三食堂一楼) |
| - buyerMessage: String                    (买家下单留言/期望面交时段)            |
| - sellerReply: String                     (卖家接单回复/确认留言)                |
|                                                                                 |
| [状态机与审计追踪]                                                              |
| - orderStatus: OrderStatus                (订单流转状态枚举)                     |
| - cancelReason: String                    (订单取消/拒绝原因)                    |
| - cancelledBy: String                     (取消方角色: BUYER / SELLER / SYSTEM)  |
| - createdTime: LocalDateTime              (买家发起订单时间)                     |
| - confirmedTime: LocalDateTime            (卖家接单确认时间)                     |
| - completedTime: LocalDateTime            (交易核验完成时间)                     |
| - cancelledTime: LocalDateTime            (交易取消/终止时间)                    |
| - updatedTime: LocalDateTime              (记录最后变更时间)                     |
+---------------------------------------------------------------------------------+
```

---

## 三、商品快照机制深度剖析

### 1. 为什么必须设计快照字段？
在二手物品流转中，存在高度动态性：
- 卖家可能在发布后修改价格（例如从 500 改为 600）；
- 卖家可能修改标题或关键描述（例如从“95新带发票”修改为“单机无票”）；
- 卖家可能在交易纠纷时下架或删除商品。

如果订单表仅保存 `goods_id` 外键，查询订单详情时实时关联 `goods` 表：
- **灾难后果 1**：历史订单展示的价格和内容会随商品编辑而动态改变，买卖双方对约定价格各执一词；
- **灾难后果 2**：若商品被逻辑删除，关联查询将报错或展示空白，历史凭据彻底丢失。

### 2. 快照固化策略
在买家提交订单的事务瞬间：
- 读取当前 `goods` 记录的 `title`、`price`、`cover_image`；
- 写入订单的 `goods_title_snapshot`、`goods_price_snapshot`、`goods_image_snapshot`；
- 订单持久化后，快照字段设为**只读，永久不可变更**。

---

## 四、订单号生成规范 (`order_no`)

- **格式标准**：`ORD` + `yyyyMMddHHmmss` + `4位随机/自增序号`
  - 示例：`ORD202609171730009527`
- **特性保障**：
  1. **全局唯一**：数据库唯一索引 `uk_trade_order_no` 强制约束；
  2. **趋势递增**：基于时间戳前缀，便于 B-Tree 索引聚集与分段归档；
  3. **非敏感性**：不暴露自增主键，防止外部爬虫穷举遍历交易单量；
  4. **可读性强**：客服介入或当面核对时，易于双方口头或文字报号。

---

## 五、领域行为与生命周期方法设计

聚合根内聚核心业务规则，杜绝“贫血模型”导致的逻辑泄露：

1. **`create(buyer, goods, meetLocation, buyerMessage)`**：
   - 校验买家非卖家：`buyer.getId() != goods.getSellerId()`；
   - 校验买家校园认证状态：`buyer.isVerified() == true`；
   - 校验商品状态：`goods.getStatus().equals("ON_SALE")`；
   - 捕获快照并生成初始状态 `WAIT_SELLER_CONFIRM`；
   - 触发商品状态锁定 `goods.lock()`。
2. **`confirmBySeller(seller, reply)`**：
   - 校验当前操作人是否为卖家：`seller.getId().equals(this.sellerId)`；
   - 校验当前状态必须为 `WAIT_SELLER_CONFIRM`；
   - 流转状态为 `WAIT_MEET`，记录 `confirmedTime` 与卖家留言。
3. **`cancelByBuyer(buyer, reason)`**：
   - 校验当前操作人是否为买家：`buyer.getId().equals(this.buyerId)`；
   - 校验当前状态为 `WAIT_SELLER_CONFIRM` 或 `WAIT_MEET`；
   - 流转状态为 `CANCELLED`，记录取消原因，触发商品解锁 `goods.unlock()`。
4. **`cancelBySeller(seller, reason)`**：
   - 校验当前操作人是否为卖家：`seller.getId().equals(this.sellerId)`；
   - 校验当前状态为 `WAIT_SELLER_CONFIRM`（拒绝接单）或 `WAIT_MEET`（无法履约）；
   - 流转状态为 `CANCELLED`，记录取消原因，触发商品解锁 `goods.unlock()`。
5. **`complete(operatorUser)`**：
   - 校验当前操作人必须为买家或卖家；
   - 校验当前状态必须为 `WAIT_MEET`；
   - 流转状态为 `COMPLETED`，记录 `completedTime`；
   - 触发商品状态标记为已售出 `goods.markSold()`；
   - 触发信用档案交易次数累加领域事件 `TradeCompletedEvent(buyerId, sellerId)`。
