package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.entity.BrowseHistory;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.BrowseHistoryMapper;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.BrowseHistoryService;
import com.campustrade.service.UserService;
import com.campustrade.vo.BrowseHistoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 浏览历史业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowseHistoryServiceImpl implements BrowseHistoryService {

    private final BrowseHistoryMapper browseHistoryMapper;
    private final GoodsMapper goodsMapper;
    private final GoodsImageMapper goodsImageMapper;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordBrowse(Long userId, Long goodsId) {
        if (userId == null || goodsId == null) {
            return;
        }

        // 单语句 upsert：并发首次浏览同一商品时，"先查后插"会撞 uk_browse_history_user_goods
        // 唯一约束并冒泡成 500（PostgreSQL 下事务还会被打成 aborted）。upsert 天然幂等，
        // 重复浏览等价于把 browse_time 刷新为最新时间，与旧行为一致。
        LocalDateTime now = LocalDateTime.now();
        int affected = browseHistoryMapper.upsertBrowseHistory(userId, goodsId, now);
        if (affected <= 0) {
            log.warn("浏览足迹写入未影响任何行（预期 1 行）: userId={}, goodsId={}", userId, goodsId);
        }
    }

    @Override
    public IPage<BrowseHistoryVO> pageHistory(int page, int size) {
        User currentUser = getCurrentUser();
        Long userId = currentUser.getId();

        int current = (page > 0) ? page : 1;
        int pageSize = (size > 0) ? Math.min(size, 100) : 10;

        Page<BrowseHistory> historyPage = new Page<>(current, pageSize);
        IPage<BrowseHistory> paged = browseHistoryMapper.selectPage(
                historyPage,
                new LambdaQueryWrapper<BrowseHistory>()
                        .eq(BrowseHistory::getUserId, userId)
                        .orderByDesc(BrowseHistory::getBrowseTime)
        );

        List<BrowseHistory> records = paged.getRecords();
        if (records.isEmpty()) {
            Page<BrowseHistoryVO> empty = new Page<>(current, pageSize, paged.getTotal());
            empty.setRecords(Collections.emptyList());
            return empty;
        }

        Set<Long> goodsIds = records.stream().map(BrowseHistory::getGoodsId).collect(Collectors.toSet());
        List<Goods> goodsList = goodsMapper.selectBatchIds(goodsIds);
        Map<Long, Goods> goodsMap = goodsList.stream().collect(Collectors.toMap(Goods::getId, g -> g));

        List<GoodsImage> images = goodsImageMapper.selectList(
                new LambdaQueryWrapper<GoodsImage>()
                        .in(GoodsImage::getGoodsId, goodsIds)
                        .orderByAsc(GoodsImage::getSort)
        );
        Map<Long, String> firstImageMap = new HashMap<>();
        if (images != null) {
            for (GoodsImage img : images) {
                firstImageMap.putIfAbsent(img.getGoodsId(), img.getImageUrl());
            }
        }

        List<BrowseHistoryVO> voList = new ArrayList<>();
        for (BrowseHistory h : records) {
            Goods g = goodsMap.get(h.getGoodsId());
            if (g != null) {
                voList.add(BrowseHistoryVO.builder()
                        .id(h.getId())
                        .goodsId(g.getId())
                        .title(g.getTitle())
                        .price(g.getPrice())
                        .conditionLevel(g.getConditionLevel())
                        .status(g.getStatus())
                        .firstImageUrl(firstImageMap.get(g.getId()))
                        .location(g.getLocation())
                        .browseTime(h.getBrowseTime())
                        .build());
            }
        }

        Page<BrowseHistoryVO> resultPage = new Page<>(current, pageSize, paged.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    private User getCurrentUser() {
        String username = SecurityUtils.getCurrentUsername();
        User user = userService.getByUsername(username);
        if (user == null) {
            throw new BusinessException(401, "用户未登录");
        }
        return user;
    }
}
