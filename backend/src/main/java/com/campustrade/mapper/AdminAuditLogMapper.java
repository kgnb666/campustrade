package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员审计日志数据访问 Mapper 接口
 */
@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLog> {
}
