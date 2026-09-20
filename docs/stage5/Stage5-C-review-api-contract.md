# Stage 5-C: 评价系统 RESTful API 契约设计规范

> **文档标识**：`docs/stage5/Stage5-C-review-api-contract.md`  
> **阶段**：Stage 5-C（评价系统设计与信用联动）  
> **性质**：前后端与跨服务 API 接口协议规范  

---

## 一、API 接口清单汇总

| 请求方法 | 接口路径 | 鉴权要求 | 接口用途 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reviews` | 需登录 (JWT) | 买家或卖家对已完成订单提交交易评价 |
| `GET` | `/api/orders/{orderId}/reviews` | 需登录 (JWT) | 获取指定订单的双向评价状态与详情 |
| `GET` | `/api/reviews/users/{userId}` | 公开/可选鉴权 | 分页查询指定用户收到的公开评价列表 (个人信用页) |
| `GET` | `/api/goods/{goodsId}/reviews` | 公开/可选鉴权 | 分页查询指定商品历史收到的评价列表 |

---

## 二、详细接口契约设计

### 2.1 提交交易评价 (`POST /api/reviews`)

#### Request Header
```http
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

#### Request Body (`CreateReviewDTO`)
```json
{
  "orderId": 100234,
  "score": 5,
  "content": "卖家同学非常守时，在图书馆门口面交，平板成色跟描述完全一致，还贴心送了保护壳！",
  "tags": ["守时诚信", "成色极佳", "沟通友好"],
  "isAnonymous": false
}
```

#### 参数校验约束 (Jakarta Validation)
- `orderId`: `@NotNull(message = "订单ID不能为空")`
- `score`: `@NotNull(message = "评分星级不能为空")`, `@Min(value = 1, message = "最低评分1星")`, `@Max(value = 5, message = "最高评分5星")`
- `content`: `@Size(max = 500, message = "评价内容不能超过500字")`
- `tags`: 列表元素最多 5 个，每个标签字符长度 `<= 20`
- `isAnonymous`: `@NotNull`，默认为 `false`

#### Response Body (`ApiResponse<ReviewVO>`)
```json
{
  "code": 200,
  "message": "评价发表成功",
  "data": {
    "id": 5001,
    "orderId": 100234,
    "goodsId": 8012,
    "reviewerId": 1001,
    "reviewerNickname": "计算机小王",
    "reviewerAvatar": "https://campustrade.oss.../avatar.jpg",
    "reviewedUserId": 1002,
    "score": 5,
    "content": "卖家同学非常守时，在图书馆门口面交，平板成色跟描述完全一致，还贴心送了保护壳！",
    "tags": ["守时诚信", "成色极佳", "沟通友好"],
    "isAnonymous": false,
    "status": "VISIBLE",
    "createdTime": "2026-09-17T23:50:00"
  }
}
```

---

### 2.2 查询订单双向评价状态 (`GET /api/orders/{orderId}/reviews`)

#### Request Header
```http
Authorization: Bearer <JWT_TOKEN>
```

#### Response Body (`ApiResponse<OrderReviewStatusVO>`)
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "orderId": 100234,
    "isBuyer": true,
    "canReview": false,
    "reasonIfNotEligible": "您已经评价过该订单",
    "myReview": {
      "id": 5001,
      "score": 5,
      "content": "卖家同学非常守时...",
      "tags": ["守时诚信", "成色极佳"],
      "createdTime": "2026-09-17T23:50:00"
    },
    "peerReview": {
      "id": 5002,
      "score": 5,
      "content": "买家很爽快，好评！",
      "tags": ["爽快买家"],
      "createdTime": "2026-09-17T23:52:00"
    }
  }
}
```

---

### 2.3 分页查询用户收到的评价 (`GET /api/reviews/users/{userId}`)

#### Query Parameters
- `page`: 当前页码，默认 1
- `size`: 每页条数，默认 10 (1~50)
- `filter`: 筛选条件，可选 `ALL` (全部), `GOOD` (4~5星好评), `BAD` (1~2星差评)，默认 `ALL`

#### Response Body (`ApiResponse<PageResult<ReviewVO>>`)
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 42,
    "page": 1,
    "size": 10,
    "list": [
      {
        "id": 5001,
        "orderId": 100234,
        "goodsId": 8012,
        "goodsTitle": "iPad Air 5 64G 紫色",
        "reviewerId": 1001,
        "reviewerNickname": "校友***",
        "reviewerAvatar": "https://campustrade.oss.../default_anonymous.png",
        "score": 5,
        "content": "卖家同学非常守时，在图书馆门口面交，平板成色跟描述完全一致！",
        "tags": ["守时诚信", "成色极佳"],
        "isAnonymous": true,
        "createdTime": "2026-09-17T23:50:00"
      }
    ]
  }
}
```

---

## 三、标准错误码映射与边界处理

| HTTP 状态码 | 业务 Code | 错误信息 (`message`) | 触发场景说明 |
| :---: | :---: | :--- | :--- |
| `400` | 40001 | 参数校验失败：评分星级必须在1~5之间 | 入参字段未通过校验 |
| `401` | 40100 | 请先登录 | 未携带有效 JWT Token |
| `403` | 40301 | 越权访问：您不是该订单的买家或卖家 | 试图评价他人或未参与的订单 |
| `404` | 40401 | 订单不存在 | 传入的 `orderId` 在系统中未检索到 |
| `409` | 40901 | 您已对该订单发表过评价，不可重复评价 | 触发唯一约束冲突拦截 |
| `422` | 42201 | 订单尚未完成，暂不可评价 | 订单状态非 `COMPLETED` |
| `422` | 42202 | 订单已完成超过7天，评价通道已关闭 | 超过 168 小时评价有效窗口期 |
