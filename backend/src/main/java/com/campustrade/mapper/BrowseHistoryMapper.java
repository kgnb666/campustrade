package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.BrowseHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 浏览历史数据持久层接口
 */
@Mapper
public interface BrowseHistoryMapper extends BaseMapper<BrowseHistory> {
}
