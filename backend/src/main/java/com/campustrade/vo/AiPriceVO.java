package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * AI 二手价格建议返回 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPriceVO {

    /**
     * 建议最低售价
     */
    private BigDecimal minPrice;

    /**
     * 建议最高售价
     */
    private BigDecimal maxPrice;

    /**
     * 推荐挂牌价
     */
    private BigDecimal suggestedPrice;

    /**
     * 估价理由及行情分析
     */
    private String reason;

    /**
     * 是否触发优雅降级
     */
    private Boolean degraded;
}
