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
}
