package com.campustrade.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义方法参数注解：自动注入当前已登录认证的 User 实体
 * 消除 Controller 中重复手动调用 getCurrentUser() 样板代码
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {

    /**
     * 是否必须已登录认证
     * 默认 true: 若未登录或登录态无效，直接抛出 HTTP 401 业务异常
     * 若为 false: 未登录时解析为 null，适用于允许匿名但可选识别用户身份的接口
     */
    boolean required() default true;
}
