# Stage 4-A: 订单 API 契约设计规范 (Order API Contract)

- **服务基础路径**：`/api/orders`
- **协议与格式**：HTTPS / JSON (UTF-8)
- **认证方式**：`Authorization: Bearer <JWT_ACCESS_TOKEN>` (全部需登录认证)
- **状态码基准**：HTTP 200 + 业务 `Result<T>` 封装 (`code: 200` 为成功)

---

## 接口总览 (API Overview)

| 序号 | 接口方法 | 路径 | 接口名称 | 权限要求 | 状态变化触发 |
| :---: | :---: | :--- | :--- | :---: | :--- |
| 1 | `POST` | `/orders` | 创建交易订单 | 已认证在校生 | `goods: ON_SALE -> LOCKED`<br>`order: -> WAIT_SELLER_CONFIRM` |
| 2 | `GET` | `/orders/my` | 我的订单列表 | 登录用户 | 无 (只读检索) |
| 3 | `GET` | `/orders/{id}` | 订单详情 | 买家或卖家本人 | 无 (只读检索) |
| 4 | `PUT` | `/orders/{id}/confirm` | 卖家确认接单 | 订单对应卖家 | `order: WAIT_SELLER_CONFIRM -> WAIT_MEET` |
| 5 | `PUT` | `/orders/{id}/cancel` | 取消订单/拒绝接单 | 订单买家或卖家 | `order: -> CANCELLED`<br>`goods: LOCKED -> ON_SALE` |
| 6 | `PUT` | `/orders/{id}/complete` | 线下核验完成交易 | 订单买家或卖家 | `order: WAIT_MEET -> COMPLETED`<br>`goods: LOCKED -> SOLD`<br>`双方 trade_count + 1` |

---

## 1. 创建交易订单 (`POST /orders`)

### 1.1 权限与说明
- **权限**：需登录，且必须通过学生身份认证；`buyer_id != seller_id`。
- **说明**：买家对在售商品发起线下自提/面交交易意向，锁定商品库存，生成订单。

### 1.2 请求参数 (`CreateOrderDTO`)
```json
{
  "goodsId": 10025,
  "meetLocation": "清华大学紫荆公寓1号楼楼下",
  "buyerMessage": "同学你好，今天下午5点至7点方便在宿舍楼下面交吗？"
}
```
- 字段约束：
  - `goodsId`: `Long` (必填, 正整数)
  - `meetLocation`: `String` (必填, 长度 2~100 字符)
  - `buyerMessage`: `String` (选填, 长度 <= 255 字符)

### 1.3 返回结构 (`Result<OrderDetailVO>`)
```json
{
  "code": 200,
  "message": "订单创建成功，等待卖家确认",
  "data": {
    "id": 8801,
    "orderNo": "ORD202609171730009527",
    "buyerId": 1001,
    "buyerNickname": "张三同学",
    "buyerAvatar": "http://minio.../avatar1.jpg",
    "sellerId": 2002,
    "sellerNickname": "李四学长",
    "sellerAvatar": "http://minio.../avatar2.jpg",
    "goodsId": 10025,
    "goodsTitleSnapshot": "九成新 iPad 9 64G 银色",
    "goodsPriceSnapshot": 1250.00,
    "goodsImageSnapshot": "http://minio.../ipad_cover.jpg",
    "meetLocation": "清华大学紫荆公寓1号楼楼下",
    "buyerMessage": "同学你好，今天下午5点至7点方便在宿舍楼下面交吗？",
    "sellerReply": null,
    "orderStatus": "WAIT_SELLER_CONFIRM",
    "cancelReason": null,
    "createdTime": "2026-09-17T17:30:00",
    "confirmedTime": null,
    "completedTime": null,
    "cancelledTime": null
  }
}
```

---

## 2. 我的订单列表 (`GET /orders/my`)

### 2.1 权限与说明
- **权限**：登录用户；
- **说明**：分页查询当前登录用户的订单，支持按身份角色（买家/卖家）及订单状态进行多维筛选。

### 2.2 请求参数 (Query Parameters)
- `role`: `String` (选填, 可选值: `BUYER` (我买到的), `SELLER` (我卖出的), 默认返回两者聚合)
- `status`: `String` (选填, 可选值: `WAIT_SELLER_CONFIRM`, `WAIT_MEET`, `COMPLETED`, `CANCELLED`)
- `page`: `int` (选填, 默认 1)
- `size`: `int` (选填, 默认 10, 最大收敛 100)

### 2.3 返回结构 (`Result<IPage<OrderListVO>>`)
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "current": 1,
    "size": 10,
    "total": 1,
    "pages": 1,
    "records": [
      {
        "id": 8801,
        "orderNo": "ORD202609171730009527",
        "role": "BUYER",
        "peerNickname": "李四学长",
        "peerAvatar": "http://minio.../avatar2.jpg",
        "goodsId": 10025,
        "goodsTitle": "九成新 iPad 9 64G 银色",
        "goodsPrice": 1250.00,
        "goodsImage": "http://minio.../ipad_cover.jpg",
        "meetLocation": "清华大学紫荆公寓1号楼楼下",
        "orderStatus": "WAIT_SELLER_CONFIRM",
        "createdTime": "2026-09-17T17:30:00"
      }
    ]
  }
}
```

---

## 3. 订单详情 (`GET /orders/{id}`)

### 3.1 权限与说明
- **权限**：必须为当前订单的买家或卖家（越权访问严格返回 403）；
- **说明**：呈现完整订单履约详情、双方信息、商品快照、协商留言与时序时间轴。

### 3.2 返回结构 (`Result<OrderDetailVO>`)
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 8801,
    "orderNo": "ORD202609171730009527",
    "buyerId": 1001,
    "buyerNickname": "张三同学",
    "buyerPhone": "138****1234",
    "sellerId": 2002,
    "sellerNickname": "李四学长",
    "sellerPhone": "139****5678",
    "goodsId": 10025,
    "goodsTitleSnapshot": "九成新 iPad 9 64G 银色",
    "goodsPriceSnapshot": 1250.00,
    "goodsImageSnapshot": "http://minio.../ipad_cover.jpg",
    "meetLocation": "清华大学紫荆公寓1号楼楼下",
    "buyerMessage": "同学你好，今天下午5点至7点方便在宿舍楼下面交吗？",
    "sellerReply": "可以的，我下午5点半在楼下大厅等你！",
    "orderStatus": "WAIT_MEET",
    "cancelReason": null,
    "createdTime": "2026-09-17T17:30:00",
    "confirmedTime": "2026-09-17T17:35:12",
    "completedTime": null,
    "cancelledTime": null
  }
}
```

---

## 4. 卖家确认接单 (`PUT /orders/{id}/confirm`)

### 4.1 权限与说明
- **权限**：仅限订单对应卖家；
- **前置条件**：当前状态必须为 `WAIT_SELLER_CONFIRM`；
- **状态变化**：`orderStatus -> WAIT_MEET`。

### 4.2 请求参数 (`ConfirmOrderDTO`)
```json
{
  "sellerReply": "可以的，我下午5点半在楼下大厅等你！"
}
```
- `sellerReply`: `String` (选填, 最大 255 字符)

### 4.3 返回结构 (`Result<Void>`)
```json
{
  "code": 200,
  "message": "卖家已接单，请按约定时间线下验货面交",
  "data": null
}
```

---

## 5. 取消订单 / 拒绝接单 (`PUT /orders/{id}/cancel`)

### 5.1 权限与说明
- **权限**：买家或卖家本人；
- **前置条件**：状态为 `WAIT_SELLER_CONFIRM` 或 `WAIT_MEET`（已完成 `COMPLETED` 严禁取消）；
- **状态变化**：
  - `orderStatus -> CANCELLED`
  - 自动触发关联商品解冻回售：`goods.status -> ON_SALE`。

### 5.2 请求参数 (`CancelOrderDTO`)
```json
{
  "cancelReason": "临时有选修课考试，无法按时面交，深感抱歉！"
}
```
- `cancelReason`: `String` (必填, 长度 2~255 字符)

### 5.3 返回结构 (`Result<Void>`)
```json
{
  "code": 200,
  "message": "订单已取消，商品已恢复在售状态",
  "data": null
}
```

---

## 6. 线下验货完成交易 (`PUT /orders/{id}/complete`)

### 6.1 权限与说明
- **权限**：买家或卖家本人；
- **前置条件**：当前状态必须为 `WAIT_MEET`；
- **状态变化**：
  - `orderStatus -> COMPLETED`；
  - `goods.status -> SOLD`；
  - 买家与卖家双方信用档案自动沉淀：`trade_count = trade_count + 1`。

### 6.2 请求参数
- 无需 Body 请求体，路径参数带订单 ID 即可。

### 6.3 返回结构 (`Result<Void>`)
```json
{
  "code": 200,
  "message": "交易完成！双方信用档案已成功累加",
  "data": null
}
```
