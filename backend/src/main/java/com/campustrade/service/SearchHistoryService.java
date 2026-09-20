package com.campustrade.service;

import java.util.List;

/**
 * 搜索历史与热搜词服务接口
 */
public interface SearchHistoryService {

    /**
     * 记录用户搜索行为并累加热搜词权重
     *
     * @param userId  用户 ID (若未登录可传 null)
     * @param keyword 搜索关键词
     */
    void recordSearch(Long userId, String keyword);

    /**
     * 获取当前全站热门搜索词排行榜
     *
     * @param limit 返回条数
     * @return 热门搜索词列表
     */
    List<String> getHotSearches(int limit);

    /**
     * 获取用户个人最近搜索词
     *
     * @param userId 用户 ID
     * @param limit  条数限制
     * @return 最近搜索词列表
     */
    List<String> getUserRecentSearches(Long userId, int limit);
}
