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
     * 商品浏览量刷盘任务的分布式互斥锁 (String 类型, SET NX PX)
     * 格式: goods:views:sync:lock
     * 值: 持有者随机令牌 (仅令牌匹配者可以释放，避免误删他人的锁)
     * 作用: 多实例同时部署时，同一时刻只有一个实例执行刷盘，避免重复扣减 Redis 增量
     */
    public static final String GOODS_VIEW_SYNC_LOCK = "goods:views:sync:lock";

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
     * 格式: student:verify:{schoolEmail} (邮箱统一 trim + 转小写)
     * 值: 6 位数字验证码明文（仅存 Redis，不落库、不进响应、不进日志）
     * 存活时间 (TTL): 5 分钟；核销成功或核验失败次数达到上限后立即删除
     */
    public static final String STUDENT_VERIFY_PREFIX = "student:verify:";

    /**
     * 学生身份认证验证码核验失败次数前缀 (String 类型)
     * 格式: student:verify:fail:{userId}:{schoolEmail} (邮箱统一 trim + 转小写)
     * 值: 当前验证码的连续核验失败次数 (INCR)
     * 存活时间 (TTL): 5 分钟（与验证码 TTL 对齐，每次失败刷新）
     * 说明: 累计达到上限（默认 5 次）即作废当前验证码并删除验证码键，
     *       调用方必须重新获取验证码；重新发送验证码或核销成功都会清空本计数。
     *       <b>维度为（发起人 userId × 目标邮箱）</b>：早期只按邮箱计数，
     *       任意登录用户都可以对"别人的邮箱"连输 5 次错误验证码把对方的验证码作废。
     */
    public static final String STUDENT_VERIFY_FAIL_PREFIX = "student:verify:fail:";

    /**
     * 学生身份认证验证码发送次数（按"发起人 userId × 目标校园邮箱"）前缀 (String 类型)
     * 格式: student:verify:send:user-email:{userId}:{schoolEmail}
     * 值: 时间窗口内的验证码发送次数 (INCR)
     * 存活时间 (TTL): 24 小时（滑动窗口，每次请求刷新）
     * 说明: 同一发起人对同一校园邮箱 24 小时内最多发送 3 次，超出直接拒绝（429）。
     *       维度从"仅按邮箱"改为"发起人 × 邮箱"的原因：只按邮箱计数时，任意账号都能把
     *       他人邮箱的当日额度打满，使被攻击者当天无法完成认证，同时给对方持续投递垃圾邮件。
     */
    public static final String STUDENT_VERIFY_SEND_USER_EMAIL_PREFIX = "student:verify:send:user-email:";

    /**
     * 学生身份认证验证码发送次数（按用户）前缀 (String 类型)
     * 格式: student:verify:send:user:{userId}
     * 值: 时间窗口内的验证码发送次数 (INCR)
     * 存活时间 (TTL): 10 分钟（滑动窗口，每次请求刷新）
     * 说明: 同一用户 10 分钟内最多发送 3 次，超出直接拒绝（429），用于阻断"换邮箱轰炸同一账号"
     */
    public static final String STUDENT_VERIFY_SEND_USER_PREFIX = "student:verify:send:user:";

    /**
     * 「无邮箱通道」认证材料的提交限流键（按用户计）。
     *
     * <p>人工审核通道没有邮件成本，但有**管理员的时间成本**：不限流时一个账号可以反复
     * 提交/撤回材料，把审核队列刷满。它与邮箱通道的发送限流分开计数，避免两条通道互相挤占额度。</p>
     */
    public static final String STUDENT_VERIFY_MANUAL_USER_PREFIX = "student:verify:manual:user:";

    /**
     * AI 商品助手每日调用配额前缀 (String 类型)
     * 格式: ai:quota:day:{userId}:{yyyyMMdd}
     * 值: 当日已调用次数 (INCR)
     * 存活时间 (TTL): 24 小时（首次计数时设置；此后每次调用都会刷新 TTL，避免出现"永不失效"的计数键）
     * 说明: 按 userId 计日配额（默认 50 次/天）。/ai/** 三个接口只要求登录、原先没有任何配额，
     *       批量注册的账号可以无限燃烧 DeepSeek 额度（直接成本）。
     */
    public static final String AI_QUOTA_DAY_PREFIX = "ai:quota:day:";

    /**
     * AI 商品助手短时频控前缀 (String 类型)
     * 格式: ai:quota:minute:{userId}:{epochMinute}
     * 值: 当前分钟内的调用次数 (INCR)
     * 存活时间 (TTL): 60 秒
     * 说明: 按 userId 计短时频率（默认 10 次/分钟），防止单账号脚本化瞬时打满日配额。
     */
    public static final String AI_QUOTA_MINUTE_PREFIX = "ai:quota:minute:";

    /**
     * /auth/refresh 按来源 IP 的限流前缀 (String 类型)
     * 格式: auth:refresh:ip:{ip}（ip 由可信代理解析得到，见 ClientIpUtils）
     * 值: 时间窗口内的请求次数 (INCR)
     * 存活时间 (TTL): 60 秒
     * 说明: 默认 30 次/分钟/IP。refresh 未认证即可调用，每次都要做 JWT 校验，
     *       无限制时可被用来做签名校验的 CPU 消耗放大。
     */
    public static final String AUTH_REFRESH_IP_PREFIX = "auth:refresh:ip:";

    /**
     * /auth/refresh 按令牌指纹的限流前缀 (String 类型)
     * 格式: auth:refresh:token:{sha256(refreshToken) 前 16 位}
     * 值: 时间窗口内的请求次数 (INCR)
     * 存活时间 (TTL): 60 秒
     * 说明: 默认 10 次/分钟/指纹。只存摘要前缀，不存令牌明文（与 jwt:refresh 会话同一原则）。
     */
    public static final String AUTH_REFRESH_TOKEN_PREFIX = "auth:refresh:token:";

    /**
     * /auth/logout 按来源 IP 的限流前缀 (String 类型)
     * 格式: auth:logout:ip:{ip}
     * 值: 时间窗口内的请求次数 (INCR)
     * 存活时间 (TTL): 60 秒
     * 说明: 默认 30 次/分钟/IP。logout 未认证即可调用，每次同样要解析 JWT。
     */
    public static final String AUTH_LOGOUT_IP_PREFIX = "auth:logout:ip:";

    /**
     * /auth/logout 按令牌指纹的限流前缀 (String 类型)
     * 格式: auth:logout:token:{sha256(bearerToken) 前 16 位}
     * 值: 时间窗口内的请求次数 (INCR)
     * 存活时间 (TTL): 60 秒
     * 说明: 默认 10 次/分钟/指纹。
     */
    public static final String AUTH_LOGOUT_TOKEN_PREFIX = "auth:logout:token:";

    /**
     * 用户每日举报次数计数前缀 (String 类型)
     * 格式: report:daily:limit:{userId}:{yyyyMMdd}
     * 值: 当日已提交举报次数 (INCR)
     * 存活时间 (TTL): 24 小时（每次 INCR 都会刷新，保证键不会因中途失败而永久存在）
     * 说明: 每日上限 10 次；Redis 不可用时降级为按 userId 查数据库当日计数（见 ReportServiceImpl）。
     */
    public static final String REPORT_DAILY_LIMIT_PREFIX = "report:daily:limit:";

    // =========================================================================
    // 统一 Key 构造器方法
    // =========================================================================

    public static String goodsFavoriteKey(Long goodsId) {
        return GOODS_FAVORITE_PREFIX + goodsId;
    }

    public static String goodsViewKey(Long goodsId) {
        return GOODS_VIEW_PREFIX + goodsId;
    }

    public static String goodsViewSyncLockKey() {
        return GOODS_VIEW_SYNC_LOCK;
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

    public static String studentVerifyKey(String schoolEmail) {
        return STUDENT_VERIFY_PREFIX + schoolEmail;
    }

    /**
     * 核验失败计数键：维度为（发起人 userId × 目标邮箱），避免他人作废本人的验证码。
     */
    public static String studentVerifyFailKey(Long userId, String schoolEmail) {
        return STUDENT_VERIFY_FAIL_PREFIX + userId + ":" + schoolEmail;
    }

    public static String studentVerifyManualUserKey(Long userId) {
        return STUDENT_VERIFY_MANUAL_USER_PREFIX + userId;
    }

    /**
     * 发送次数计数键：维度为（发起人 userId × 目标邮箱），24 小时窗口 3 次。
     */
    public static String studentVerifySendUserEmailKey(Long userId, String schoolEmail) {
        return STUDENT_VERIFY_SEND_USER_EMAIL_PREFIX + userId + ":" + schoolEmail;
    }

    public static String studentVerifySendUserKey(Long userId) {
        return STUDENT_VERIFY_SEND_USER_PREFIX + userId;
    }

    /** AI 日配额键：{@code ai:quota:day:{userId}:{yyyyMMdd}}。 */
    public static String aiQuotaDayKey(Long userId, String day) {
        return AI_QUOTA_DAY_PREFIX + userId + ":" + day;
    }

    /** AI 分钟频控键：{@code ai:quota:minute:{userId}:{epochMinute}}。 */
    public static String aiQuotaMinuteKey(Long userId, long epochMinute) {
        return AI_QUOTA_MINUTE_PREFIX + userId + ":" + epochMinute;
    }

    /** /auth/refresh 按来源 IP 的限流键。 */
    public static String authRefreshIpKey(String ip) {
        return AUTH_REFRESH_IP_PREFIX + ip;
    }

    /** /auth/refresh 按令牌指纹（sha256 前 16 位）的限流键。 */
    public static String authRefreshTokenKey(String fingerprint) {
        return AUTH_REFRESH_TOKEN_PREFIX + fingerprint;
    }

    /** /auth/logout 按来源 IP 的限流键。 */
    public static String authLogoutIpKey(String ip) {
        return AUTH_LOGOUT_IP_PREFIX + ip;
    }

    /** /auth/logout 按令牌指纹（sha256 前 16 位）的限流键。 */
    public static String authLogoutTokenKey(String fingerprint) {
        return AUTH_LOGOUT_TOKEN_PREFIX + fingerprint;
    }

    /** 每日举报计数键：{@code report:daily:limit:{userId}:{yyyyMMdd}}。 */
    public static String reportDailyLimitKey(Long userId, String day) {
        return REPORT_DAILY_LIMIT_PREFIX + userId + ":" + day;
    }
}
