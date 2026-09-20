package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏数据持久层接口
 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {
}
