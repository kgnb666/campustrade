package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.UserCreditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 信用变更审计流水 Mapper 接口
 */
@Mapper
public interface UserCreditLogMapper extends BaseMapper<UserCreditLog> {
}
