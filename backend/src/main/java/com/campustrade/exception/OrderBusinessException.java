package com.campustrade.exception;

import com.campustrade.common.ResultCode;

/**
 * 订单业务专用异常
 */
public class OrderBusinessException extends BusinessException {

    public OrderBusinessException(String message) {
        super(ResultCode.BAD_REQUEST.getCode(), message);
    }

    public OrderBusinessException(int code, String message) {
        super(code, message);
    }

    public OrderBusinessException(ResultCode resultCode) {
        super(resultCode);
    }
}
