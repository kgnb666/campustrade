package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.CreateGoodsDTO;
import com.campustrade.dto.GoodsQueryDTO;
import com.campustrade.dto.UpdateGoodsDTO;
import com.campustrade.vo.GoodsDetailVO;
import com.campustrade.vo.GoodsListVO;

import java.util.List;

/**
 * 商品业务服务接口
 */
public interface GoodsService {

    /**
     * 发布商品
     *
     * @param dto 商品创建请求参数
     * @return 创建成功的商品 ID
     */
    Long createGoods(CreateGoodsDTO dto);

    /**
     * 分页查询商品列表
     *
     * @param queryDTO 筛选和分页参数
     * @return 分页结果
     */
    IPage<GoodsListVO> pageGoods(GoodsQueryDTO queryDTO);

    /**
     * 增强搜索商品并自动记录搜索行为与热搜权重
     *
     * @param queryDTO 搜索参数
     * @return 分页结果
     */
    IPage<GoodsListVO> searchGoods(GoodsQueryDTO queryDTO);

    /**
     * 获取商品详情并自增浏览量
     *
     * @param id 商品 ID
     * @return 商品详情
     */
    GoodsDetailVO getGoodsDetail(Long id);

    /**
     * 修改商品信息 (仅限本人)
     *
     * @param id  商品 ID
     * @param dto 修改参数
     */
    void updateGoods(Long id, UpdateGoodsDTO dto);

    /**
     * 逻辑删除商品 (流转为 OFF_SHELF, 仅限本人)
     *
     * @param id 商品 ID
     */
    void deleteGoods(Long id);

    /**
     * 修改商品状态 (仅限本人)
     *
     * @param id     商品 ID
     * @param status 目标状态 (ON_SALE, OFF_SHELF)
     */
    void updateGoodsStatus(Long id, String status);

    /**
     * 获取当前登录用户发布的全部商品
     */
    List<GoodsListVO> listMyGoods();

    /**
     * 分页获取当前登录用户发布的商品（可选能力，接口契约不变）。
     *
     * <p>历史实现一次性把卖家的全部商品读出来（无 LIMIT），商品多的卖家会产生无界查询与无界响应。
     * 这里提供分页入口：调用方显式传 page/size 时只返回该页记录，返回结构仍是 {@code List<GoodsListVO>}
     * （与 {@link #listMyGoods()} 完全一致），因此不改变任何既有客户端的行为；
     * 不传 page/size 时仍走 {@link #listMyGoods()} 的全量语义。</p>
     *
     * @param page 页码（从 1 开始）
     * @param size 每页数量（上限 100）
     * @return 分页结果（含实际命中的总条数）
     */
    IPage<GoodsListVO> pageMyGoods(Integer page, Integer size);

    /**
     * 定时或手动将 Redis 浏览量缓存同步回数据库
     */
    void syncViewCounts();
}
