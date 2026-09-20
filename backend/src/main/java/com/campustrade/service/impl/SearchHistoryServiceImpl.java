package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.util.SearchKeywordUtils;
import com.campustrade.entity.SearchHistory;
import com.campustrade.mapper.SearchHistoryMapper;
import com.campustrade.service.SearchHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 搜索历史业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryServiceImpl implements SearchHistoryService {

    /**
     * 热搜 ZSet 最大成员容量上限，防止冷僻长尾词无上限膨胀
     */
    private static final int MAX_HOT_SEARCH_MEMBERS = 1000;

    private final SearchHistoryMapper searchHistoryMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void recordSearch(Long userId, String keyword) {
        // 统一通过工具类标准化清洗与过滤无意义空白/超长字符
        String normalized = SearchKeywordUtils.normalize(keyword);
        if (normalized == null) {
            return;
        }

        // 1. 若用户已登录，保存个人搜索历史至 PostgreSQL (Source of Truth)
        if (userId != null) {
            try {
                SearchHistory history = SearchHistory.builder()
                        .userId(userId)
                        .keyword(normalized)
                        .searchTime(LocalDateTime.now())
                        .build();
                searchHistoryMapper.insert(history);
            } catch (Exception e) {
                log.warn("保存用户 [{}] 搜索历史失败: {}", userId, e.getMessage());
            }
        }

        // 2. Redis ZSet 累加热搜权重 (ZINCRBY)
        try {
            String hotKey = RedisKeyConstants.searchHotKey();
            redisTemplate.opsForZSet().incrementScore(hotKey, normalized, 1.0);

            // 热搜容量轻量自愈与淘汰：若 ZSet 成员超过上限，裁剪末尾低热度成员
            Long size = redisTemplate.opsForZSet().size(hotKey);
            if (size != null && size > MAX_HOT_SEARCH_MEMBERS) {
                long removeCount = size - MAX_HOT_SEARCH_MEMBERS;
                redisTemplate.opsForZSet().removeRange(hotKey, 0, removeCount - 1);
            }
        } catch (Exception e) {
            log.warn("更新 Redis 热搜词榜单失败: {}", e.getMessage());
        }
    }

    @Override
    public List<String> getHotSearches(int limit) {
        int max = (limit > 0) ? Math.min(limit, 100) : 10;
        try {
            String hotKey = RedisKeyConstants.searchHotKey();
            Set<Object> range = redisTemplate.opsForZSet().reverseRange(hotKey, 0, max - 1);
            if (range != null && !range.isEmpty()) {
                return range.stream().map(Object::toString).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("读取 Redis 热搜词失败: {}", e.getMessage());
        }

        // 默认预设校园热门搜索推荐 (Redis 丢失或冷启动时的优雅降级保底)
        return List.of("iPad", "考研英语", "自行车", "MacBook", "电动车", "高数教材", "宿舍小煮锅", "显示器");
    }

    @Override
    public List<String> getUserRecentSearches(Long userId, int limit) {
        if (userId == null) {
            return new ArrayList<>();
        }

        int max = (limit > 0) ? Math.min(limit, 100) : 10;
        List<SearchHistory> list = searchHistoryMapper.selectList(
                new LambdaQueryWrapper<SearchHistory>()
                        .eq(SearchHistory::getUserId, userId)
                        .orderByDesc(SearchHistory::getSearchTime)
                        .last("LIMIT " + max * 2)
        );

        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }

        // 去重并保持顺序
        return list.stream()
                .map(SearchHistory::getKeyword)
                .distinct()
                .limit(max)
                .collect(Collectors.toList());
    }
}
