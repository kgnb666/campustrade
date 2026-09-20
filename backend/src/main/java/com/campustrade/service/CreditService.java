package com.campustrade.service;

import com.campustrade.entity.UserCredit;
import com.campustrade.enums.CreditChangeType;

/**
 * 信用领域核心服务接口
 */
public interface CreditService {

    /**
     * 获取或初始化用户信用档案
     *
     * @param userId 用户ID
     * @return 用户信用档案实体
     */
    UserCredit getOrCreateCredit(Long userId);

    /**
     * 增加信用积分 (事务保证，支持幂等拦截)
     *
     * @param userId      用户ID
     * @param score       增加分值 (必须 > 0)
     * @param type        变动类型
     * @param relatedType 关联业务类型 (如 ORDER)
     * @param relatedId   关联业务ID (如 orderId)
     * @param reason      变动原因
     * @return 更新后的用户信用档案实体
     */
    UserCredit addCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason
    );

    /**
     * 扣除信用积分 (事务保证，支持幂等拦截)
     *
     * @param userId      用户ID
     * @param score       扣除分值 (必须 > 0)
     * @param type        变动类型
     * @param relatedType 关联业务类型 (如 ORDER)
     * @param relatedId   关联业务ID (如 orderId)
     * @param reason      扣除原因
     * @return 更新后的用户信用档案实体
     */
    UserCredit deductCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason
    );

    /**
     * 增加信用积分，并显式指定"业务动作标识"参与幂等键计算。
     *
     * <p><b>幂等键语义</b>：{@code changeType|relatedType|relatedId|actionKey}。
     * 同一次业务动作（含管理员治理动作）无论被重试多少次都得到同一个键，因此只生效一次；
     * 不同次动作（例如"屏蔽 → 恢复 → 再次屏蔽"）只要 {@code actionKey} 不同就各自生效。</p>
     *
     * @param actionKey 业务动作唯一标识（如治理动作对应的审计日志 ID）；传 null 时退化为
     *                  "按 changeType + relatedType + relatedId 幂等"，与历史行为一致
     */
    UserCredit addCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    );

    /**
     * 扣除信用积分，并显式指定"业务动作标识"参与幂等键计算（语义同
     * {@link #addCredit(Long, Integer, CreditChangeType, String, Long, String, String)}）。
     */
    UserCredit deductCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    );

    /**
     * 按<b>有符号幅度</b>调整信用积分：正数加分、负数扣分、0 不产生任何变动与流水。
     *
     * <p>本方法是 {@code CreditRule} 定义的规则与信用领域之间的唯一接入口：
     * 规则类给出"应当变动多少"（可为负），本方法负责把方向落到加/扣两条既有实现上，
     * 因此调用方不需要再写 {@code if (delta > 0) addCredit else deductCredit} 这类分支
     * —— 那正是三处评价信用路径此前各自实现一遍、并且容易写反的地方。</p>
     *
     * <p>幂等键、区间截断与审计流水语义与 {@link #addCredit} / {@link #deductCredit} 完全一致。</p>
     *
     * @param delta 有符号变动幅度（正=加分，负=扣分，0=不变）
     */
    UserCredit applyDelta(
            Long userId,
            Integer delta,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    );
}
