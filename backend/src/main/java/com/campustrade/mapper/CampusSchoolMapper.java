package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.CampusSchool;
import org.apache.ibatis.annotations.Mapper;

/**
 * 高校字典表 Mapper 接口
 */
@Mapper
public interface CampusSchoolMapper extends BaseMapper<CampusSchool> {
}
