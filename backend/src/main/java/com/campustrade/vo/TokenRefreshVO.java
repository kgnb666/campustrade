package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Token 刷新成功响应 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenRefreshVO implements Serializable {

    private String accessToken;
    private String refreshToken;
}
