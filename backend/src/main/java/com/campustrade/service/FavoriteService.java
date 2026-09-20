package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.vo.FavoriteVO;

/**
 * 商品收藏服务接口
 */
public interface FavoriteService {

    /**
     * 收藏商品
     *
     * @param goodsId 商品 ID
     */
    void addFavorite(Long goodsId);

    /**
     * 取消收藏商品
     *
     * @param goodsId 商品 ID
     */
    void removeFavorite(Long goodsId);

    /**
     * 分页查询当前用户收藏列表
     *
     * @param page 页码
     * @param size 每页大小
     * @return 收藏商品分页数据
     */
    IPage<FavoriteVO> pageFavorites(int page, int size);

    /**
     * 检查当前用户是否已收藏指定商品
     *
     * @param goodsId 商品 ID
     * @return 是否收藏
     */
    boolean isFavorite(Long goodsId);

    /**
     * 获取指定商品的被收藏总数 (优先走 Redis 缓存)
     *
     * @param goodsId 商品 ID
     * @return 收藏数
     */
    long getFavoriteCount(Long goodsId);

    /**
     * 从数据库重新计算真实收藏数并刷新回填 Redis 缓存
     *
     * @param goodsId 商品 ID
     * @return 数据库真实收藏数
     */
    long refreshFavoriteCount(Long goodsId);
}
