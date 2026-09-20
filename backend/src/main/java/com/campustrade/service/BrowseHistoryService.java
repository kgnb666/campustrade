package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.vo.BrowseHistoryVO;

/**
 * 浏览历史服务接口
 */
public interface BrowseHistoryService {

    /**
     * 记录或更新用户对商品的浏览时间
     *
     * @param userId  用户 ID
     * @param goodsId 商品 ID
     */
    void recordBrowse(Long userId, Long goodsId);

    /**
     * 分页获取当前用户的浏览历史 (按浏览时间倒序)
     *
     * @param page 页码
     * @param size 每页条数
     * @return 浏览历史分页列表
     */
    IPage<BrowseHistoryVO> pageHistory(int page, int size);
}
