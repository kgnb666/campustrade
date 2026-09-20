package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Report;
import org.apache.ibatis.annotations.Mapper;

/**
 * 举报工单数据访问 Mapper 接口
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
