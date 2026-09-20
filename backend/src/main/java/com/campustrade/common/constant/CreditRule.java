package com.campustrade.common.constant;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信用领域业务规则 —— 数值与窗口的<b>唯一真相源</b>。
 *
 * <h2>为什么需要它</h2>
 * 同一条信用规则此前在多处各自实现：星级对应分值在评价监听器里写一遍、在管理员"屏蔽冲正"里
 * 反着写一遍、在"恢复补偿"里再写一遍；7 天评价窗口在 {@code ReviewServiceImpl} 两处各写一个
 * {@code 168}。任何一处调整都会让另一处悄悄失配，而且失配只会在线上表现为"分数对不上"。
 * 现在所有数值与判定都收敛到本类，调用方只表达"要做什么"，不再复述"是多少"。
 *
 * <p><b>本类只承载既有规则的数值，不引入任何新的业务规则，也不修改任何数值。</b></p>
 *
 * <h2>三条评价信用路径的对称性</h2>
 * <ul>
 *   <li>创建评价：{@link #reviewDeltaForScore(int)} —— 5星 +3 / 4星 +1 / 3星 0 / 2星 -2 / 1星 -5</li>
 *   <li>屏蔽冲正：{@link #reviewReversalDeltaForScore(int)} —— 原值的精确取反
 *       （好评追缴、差评补回）</li>
 *   <li>恢复补偿：{@link #reviewRestoreDeltaForScore(int)} —— 与创建时完全一致的原值</li>
 * </ul>
 * 三者由同一个 {@link #REVIEW_STAR_DELTA} 表派生，因此
 * {@code 创建 + 冲正 == 0}（被屏蔽的评价不再贡献分数）且
 * {@code 冲正 + 恢复 == 0}（恢复回到"评价正常展示"的分数）。
 */
public final class CreditRule {

    private CreditRule() {
        // 规则常量与纯函数，禁止实例化
    }

    // =========================================================================
    // 信用分区间与默认值
    // =========================================================================

    /** 信用分下限（触底后继续扣分的实际生效幅度为 0，保证对账恒等式成立）。 */
    public static final int SCORE_MIN = 0;

    /** 信用分上限。 */
    public static final int SCORE_MAX = 200;

    /** 新注册用户的初始信用分。 */
    public static final int SCORE_DEFAULT = 100;

    // =========================================================================
    // 订单完成
    // =========================================================================

    /** 订单面交完成后，买家与卖家<b>各自</b>获得的信用分。 */
    public static final int TRADE_COMPLETED_BONUS = 2;

    /** 订单在面交阶段（WAIT_MEET）违约取消时，发起方的信用分扣减幅度。 */
    public static final int TRADE_CANCEL_PENALTY = 1;

    // =========================================================================
    // 评价窗口
    // =========================================================================

    /** 订单完成后的可评价窗口（天）。 */
    public static final int REVIEW_WINDOW_DAYS = 7;

    /** 订单完成后的可评价窗口（小时，= {@link #REVIEW_WINDOW_DAYS} * 24，仅用于展示与日志）。 */
    public static final long REVIEW_WINDOW_HOURS = REVIEW_WINDOW_DAYS * 24L;

    /**
     * 评价窗口是否已过期。
     *
     * <p>语义与既有实现完全一致：{@code completedTime} 为空时视为"窗口内"（拦不住也就放行，
     * 由订单状态机本身保证只有 COMPLETED 订单才会走到这里）。</p>
     *
     * @param completedTime 订单完成时间（可为 null）
     * @param now           判定时刻
     * @return true = 已超出窗口，评价通道关闭
     */
    public static boolean isReviewWindowExpired(LocalDateTime completedTime, LocalDateTime now) {
        return completedTime != null && now != null
                && now.isAfter(completedTime.plusDays(REVIEW_WINDOW_DAYS));
    }

    // =========================================================================
    // 星级 → 信用变动
    // =========================================================================

    /**
     * 星级对应的信用变动表（正数加分、负数扣分、0 不变）。
     *
     * <p>用 {@link LinkedHashMap} 固化顺序，便于日志与测试稳定遍历。</p>
     */
    private static final Map<Integer, Integer> REVIEW_STAR_DELTA;

    static {
        Map<Integer, Integer> deltas = new LinkedHashMap<>();
        deltas.put(5, 3);
        deltas.put(4, 1);
        deltas.put(3, 0);
        deltas.put(2, -2);
        deltas.put(1, -5);
        REVIEW_STAR_DELTA = Collections.unmodifiableMap(deltas);
    }

    /** 全部纳入规则的星级（5 → 1，由高到低）。 */
    public static final int STAR_MAX = 5;
    public static final int STAR_MIN = 1;

    /**
     * 创建评价时被评价人的信用变动。
     *
     * @param score 星级
     * @return 正数表示加分、负数表示扣分、0 表示不调整
     */
    public static int reviewDeltaForScore(int score) {
        Integer delta = REVIEW_STAR_DELTA.get(score);
        if (delta == null) {
            throw new IllegalArgumentException("未纳入信用规则的星级: " + score);
        }
        return delta;
    }

    /**
     * 评价被管理员屏蔽时的倒扣冲正幅度：与创建时的分值精确等值反向。
     *
     * <p>好评（原加分）→ 负数（追缴）；差评（原扣分）→ 正数（补回）；3星 → 0。</p>
     */
    public static int reviewReversalDeltaForScore(int score) {
        return -reviewDeltaForScore(score);
    }

    /**
     * 评价被管理员恢复展示时的补偿幅度：与创建时的分值完全一致。
     */
    public static int reviewRestoreDeltaForScore(int score) {
        return reviewDeltaForScore(score);
    }

    /**
     * 判断星级是否在 {@link #STAR_MIN} ~ {@link #STAR_MAX} 之内。
     */
    public static boolean isKnownStar(int score) {
        return REVIEW_STAR_DELTA.containsKey(score);
    }
}
