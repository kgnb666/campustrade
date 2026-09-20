package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.GoodsImage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品图片 Mapper
 */
@Mapper
public interface GoodsImageMapper extends BaseMapper<GoodsImage> {

    /**
     * 批量插入商品图片（单条 INSERT ... VALUES (...),(...),(...)）。
     *
     * <h2>为什么不用循环 {@code insert()}</h2>
     * 一件商品通常有 1~9 张图，循环插入会产生 N 次网络往返 + N 次 SQL 解析：
     * 发布/编辑商品时"图片 + 标签"两组循环叠加后成为该接口最重的部分。
     * 这里合并为一条 SQL，往返次数恒为 1，与图片数量无关。
     *
     * <p>{@code id} 列刻意不出现在 INSERT 列表里：它与 {@link GoodsImage} 上的 {@code IdType.AUTO} 语义一致
     * （由数据库序列 {@code goods_image_id_seq} 分配）。</p>
     *
     * @param list 待插入图片（调用方必须保证非空；空集合会生成非法 SQL）
     * @return 实际插入行数
     */
    @Insert("<script>"
            + "INSERT INTO campus_trade.goods_image (goods_id, image_url, sort, created_time) VALUES "
            + "<foreach collection='list' item='item' separator=','>"
            + "(#{item.goodsId}, #{item.imageUrl}, #{item.sort}, #{item.createdTime})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<GoodsImage> list);
}
