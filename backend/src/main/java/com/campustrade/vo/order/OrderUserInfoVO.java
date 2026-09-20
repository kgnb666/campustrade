package com.campustrade.vo.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单关联买家/卖家脱敏信息 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderUserInfoVO implements Serializable {

    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像 URL
     */
    private String avatar;
}
