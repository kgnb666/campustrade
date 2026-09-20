package com.campustrade.dto.review;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 创建交易评价请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewRequest implements Serializable {

    /**
     * 目标完成订单ID
     */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /**
     * 评分星级: 1 ~ 5
     */
    @NotNull(message = "评分星级不能为空")
    @Min(value = 1, message = "最低评分为1星")
    @Max(value = 5, message = "最高评分为5星")
    private Integer score;

    /**
     * 评价文本内容 (最多500字)
     */
    @Size(max = 500, message = "评价内容最多500字")
    private String content;

    /**
     * 快捷标签列表 (如 ["守时诚信", "成色极佳"])
     */
    private List<String> tags;

    /**
     * 是否匿名评价 (支持 anonymous 与 isAnonymous 两种 JSON 别名)
     */
    @JsonAlias({"anonymous", "isAnonymous"})
    private Boolean anonymous;

    public Boolean getIsAnonymous() {
        return anonymous != null && anonymous;
    }
}
