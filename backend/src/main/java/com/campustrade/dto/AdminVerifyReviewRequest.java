package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员处置「无邮箱通道」认证申请的请求体。
 *
 * <p>{@code note} 在驳回时**必填**（校验放在服务层，因为它依赖 action 的取值：
 * "通过"可以不写意见，"驳回"不写原因等于让学生重猜一遍）。</p>
 */
@Data
public class AdminVerifyReviewRequest implements Serializable {

    /** APPROVE（通过）/ REJECT（驳回），大小写不敏感 */
    @NotBlank(message = "处理动作不能为空")
    private String action;

    /** 审核意见：驳回时必填，通过时可选 */
    private String note;
}
