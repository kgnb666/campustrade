package com.campustrade.common.constant;

/**
 * 统一 Redis Key 规范与常量定义
 * 遵循 key 命名层级规范: 业务模块:实体类型[:标识符]
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
        // 私有构造函数，防止实例化
    }

    /**
     * 商品收藏量计数前缀 (String 类型)
     * 格式: goods:favorite:{goodsId}
     * 值: 收藏总量 (非负整数)
     */
    public static final String GOODS_FAVORITE_PREFIX = "goods:favorite:";

    /**
     * 商品浏览量增量缓存前缀 (String 类型)
     * 格式: goods:view:{goodsId}
     * 值: 距离上次持久化至 DB 的增量浏览次数 (INCR)
     */
    public static final String GOODS_VIEW_PREFIX = "goods:view:";

    /**
     * 商品浏览量脏数据商品 ID 集合 (Set 类型)
     * 格式: goods:views:dirty_ids
     * 作用: 记录有增量浏览行为但尚未落盘至 DB 的商品 ID，消除 keys(*) 阻塞
     */
    public static final String GOODS_VIEW_DIRTY_IDS = "goods:views:dirty_ids";

    /**
     * 全站热门搜索词排行榜 (Sorted Set 类型)
     * 格式: search:hot
     * Member: 搜索关键词, Score: 累计搜索热度频次
     */
    public static final String SEARCH_HOT = "search:hot";

    /**
     * JWT Token 登出黑名单前缀 (String 类型)
     * 格式: jwt:blacklist:{token}
     * 存活时间 (TTL): Token 剩余有效时长
     */
    public static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";

    /**
     * JWT Refresh Token 前缀 (String 类型)
     * 格式: jwt:refresh:{userId}
     * 值: Refresh Token 的 SHA-256 十六进制摘要（只存摘要，绝不存令牌明文）
     * 存活时间 (TTL): 7 天（与 Refresh Token 有效期一致）
     */
    public static final String JWT_REFRESH_PREFIX = "jwt:refresh:";

    /**
     * 登录失败次数（按用户名）计数前缀 (String 类型)
     * 格式: login:fail:{username}
     * 值: 连续失败次数（登录成功即清零）
     * 存活时间 (TTL): 锁定窗口时长（默认 15 分钟）
     */
    public static final String LOGIN_FAIL_USERNAME_PREFIX = "login:fail:";

    /**
     * 登录失败次数（按来源 IP）计数前缀 (String 类型)
     * 格式: login:fail:ip:{ip}
     * 值: 连续失败次数（登录成功即清零）
     * 存活时间 (TTL): 锁定窗口时长（默认 15 分钟）
     */
    public static final String LOGIN_FAIL_IP_PREFIX = "login:fail:ip:";

    /**
     * 注册请求来源 IP 计数前缀 (String 类型)
     * 格式: register:ip:{ip}
     * 值: 当前时间窗口内的注册请求次数 (INCR)
     * 存活时间 (TTL): 1 小时
     */
    public static final String REGISTER_IP_PREFIX = "register:ip:";

    /**
     * 学生身份认证验证码前缀 (String 类型)
     * 格式: student:verify:{phone}
     * 存活时间 (TTL): 5 分钟
     */
    public static final String STUDENT_VERIFY_PREFIX = "student:verify:";

    // =========================================================================
    // 统一 Key 构造器方法
    // =========================================================================

    public static String goodsFavoriteKey(Long goodsId) {
        return GOODS_FAVORITE_PREFIX + goodsId;
    }

    public static String goodsViewKey(Long goodsId) {
        return GOODS_VIEW_PREFIX + goodsId;
    }

    public static String searchHotKey() {
        return SEARCH_HOT;
    }

    public static String jwtBlacklistKey(String token) {
        return JWT_BLACKLIST_PREFIX + token;
    }

    public static String jwtRefreshKey(Long userId) {
        return JWT_REFRESH_PREFIX + userId;
    }

    public static String loginFailUsernameKey(String username) {
        return LOGIN_FAIL_USERNAME_PREFIX + username;
    }

    public static String loginFailIpKey(String ip) {
        return LOGIN_FAIL_IP_PREFIX + ip;
    }

    public static String registerIpKey(String ip) {
        return REGISTER_IP_PREFIX + ip;
    }

    public static String studentVerifyKey(String phone) {
        return STUDENT_VERIFY_PREFIX + phone;
    }
}
