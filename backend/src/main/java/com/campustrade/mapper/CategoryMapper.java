package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品分类 Mapper
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
