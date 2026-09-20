package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 搜索历史数据持久层接口
 */
@Mapper
public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {
}
