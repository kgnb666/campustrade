package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.GoodsTag;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品标签 Mapper
 */
@Mapper
public interface GoodsTagMapper extends BaseMapper<GoodsTag> {

    /**
     * 批量插入商品标签（单条 INSERT ... VALUES (...),(...),(...)）。
     *
     * <p>与 {@link GoodsImageMapper#insertBatch(List)} 同理：把"每个标签一次往返"收敛成一次。
     * {@code id} 列同样交由数据库序列 {@code goods_tag_id_seq} 分配（对应 {@link GoodsTag} 的 {@code IdType.AUTO}）。</p>
     *
     * @param list 待插入标签（调用方必须保证非空；空集合会生成非法 SQL）
     * @return 实际插入行数
     */
    @Insert("<script>"
            + "INSERT INTO campus_trade.goods_tag (goods_id, tag_name) VALUES "
            + "<foreach collection='list' item='item' separator=','>"
            + "(#{item.goodsId}, #{item.tagName})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<GoodsTag> list);
}
