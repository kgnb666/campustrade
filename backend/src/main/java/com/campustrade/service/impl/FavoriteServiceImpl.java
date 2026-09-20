package com.campustrade.service.impl;

import com.campustrade.enums.GoodsStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.FavoriteService;
import com.campustrade.service.UserService;
import com.campustrade.vo.FavoriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品收藏业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private static final String FAVORITE_KEY_PREFIX = RedisKeyConstants.GOODS_FAVORITE_PREFIX;

    private final FavoriteMapper favoriteMapper;
    private final GoodsMapper goodsMapper;
    private final GoodsImageMapper goodsImageMapper;
    private final CampusSchoolMapper campusSchoolMapper;
    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addFavorite(Long goodsId) {
        User currentUser = getCurrentUser();
        Long userId = currentUser.getId();

        // 1. 检查商品是否存在且处于在售状态
        if (goodsId == null || goodsId <= 0) {
            throw new BusinessException(404, "商品不存在或已被删除");
        }
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null || GoodsStatus.OFF_SHELF.matches(goods.getStatus())) {
            throw new BusinessException(404, "商品不存在或已被删除");
        }

        // 2. 检查是否重复收藏
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getGoodsId, goodsId)
        );
        if (count != null && count > 0) {
            throw new BusinessException(400, "您已收藏过该商品");
        }

        // 3. 写入数据库
        Favorite favorite = Favorite.builder()
                .userId(userId)
                .goodsId(goodsId)
                .createdTime(LocalDateTime.now())
                .build();
        try {
            favoriteMapper.insert(favorite);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("并发重复收藏拦截 (唯一约束): userId={}, goodsId={}", userId, goodsId);
            throw new BusinessException(400, "您已收藏过该商品");
        }

        // 4. Redis 收藏数计数器维护 (缓存容错与缺失自愈)
        String redisKey = FAVORITE_KEY_PREFIX + goodsId;
        try {
            Boolean hasKey = redisTemplate.hasKey(redisKey);
            if (Boolean.TRUE.equals(hasKey)) {
                redisTemplate.opsForValue().increment(redisKey);
            } else {
                // Key 不存在时，不能单纯 INCR（否则若原来已有其他用户收藏，会脏写入 1）
                // 必须从 DB 重新计算准确总数回填 Redis
                refreshFavoriteCount(goodsId);
            }
        } catch (Exception e) {
            log.warn("更新 Redis 收藏计数异常 (DB 已写入): goodsId={}, error={}", goodsId, e.getMessage());
        }

        log.info("用户 [{}] 成功收藏商品 [{}]", userId, goodsId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFavorite(Long goodsId) {
        if (goodsId == null || goodsId <= 0) {
            throw new BusinessException(400, "您尚未收藏该商品");
        }

        User currentUser = getCurrentUser();
        Long userId = currentUser.getId();

        Favorite favorite = favoriteMapper.selectOne(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getGoodsId, goodsId)
        );
        if (favorite == null) {
            throw new BusinessException(400, "您尚未收藏该商品");
        }

        int deleted = favoriteMapper.deleteById(favorite.getId());
        if (deleted <= 0) {
            throw new BusinessException(400, "您尚未收藏该商品");
        }

        // Redis 递减计数维护 (缓存容错与缺失自愈)
        String redisKey = FAVORITE_KEY_PREFIX + goodsId;
        try {
            Boolean hasKey = redisTemplate.hasKey(redisKey);
            if (Boolean.TRUE.equals(hasKey)) {
                Long currentCount = redisTemplate.opsForValue().decrement(redisKey);
                if (currentCount != null && currentCount < 0) {
                    redisTemplate.opsForValue().set(redisKey, 0L);
                }
            } else {
                // Key 不存在时，不能单纯 DECR（否则会变成 -1），必须从 DB 刷新
                refreshFavoriteCount(goodsId);
            }
        } catch (Exception e) {
            log.warn("更新 Redis 收藏递减计数异常 (DB 已删除): goodsId={}, error={}", goodsId, e.getMessage());
        }

        log.info("用户 [{}] 成功取消收藏商品 [{}]", userId, goodsId);
    }

    @Override
    public IPage<FavoriteVO> pageFavorites(int page, int size) {
        User currentUser = getCurrentUser();
        Long userId = currentUser.getId();

        int current = (page > 0) ? page : 1;
        int pageSize = (size > 0) ? Math.min(size, 100) : 10;

        Page<Favorite> favoritePage = new Page<>(current, pageSize);
        IPage<Favorite> pagedFavorites = favoriteMapper.selectPage(
                favoritePage,
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .orderByDesc(Favorite::getCreatedTime)
        );

        List<Favorite> records = pagedFavorites.getRecords();
        if (records.isEmpty()) {
            Page<FavoriteVO> emptyPage = new Page<>(current, pageSize, pagedFavorites.getTotal());
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }

        // 批量查询商品信息
        Set<Long> goodsIds = records.stream().map(Favorite::getGoodsId).collect(Collectors.toSet());
        List<Goods> goodsList = goodsMapper.selectBatchIds(goodsIds);
        Map<Long, Goods> goodsMap = goodsList.stream().collect(Collectors.toMap(Goods::getId, g -> g));

        // 批量查询商品首图
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

        // 批量查询学校名称
        Set<Long> schoolIds = goodsList.stream().map(Goods::getSchoolId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> schoolMap = new HashMap<>();
        if (!schoolIds.isEmpty()) {
            List<CampusSchool> schools = campusSchoolMapper.selectBatchIds(schoolIds);
            if (schools != null) {
                schools.forEach(s -> schoolMap.put(s.getId(), s.getSchoolName()));
            }
        }

        List<FavoriteVO> voList = new ArrayList<>();
        for (Favorite fav : records) {
            Goods g = goodsMap.get(fav.getGoodsId());
            if (g != null) {
                voList.add(FavoriteVO.builder()
                        .id(fav.getId())
                        .goodsId(g.getId())
                        .title(g.getTitle())
                        .price(g.getPrice())
                        .originalPrice(g.getOriginalPrice())
                        .conditionLevel(g.getConditionLevel())
                        .status(g.getStatus())
                        .firstImageUrl(firstImageMap.get(g.getId()))
                        .location(g.getLocation())
                        .sellerId(g.getSellerId())
                        .schoolName(schoolMap.get(g.getSchoolId()))
                        .createdTime(fav.getCreatedTime())
                        .build());
            }
        }

        Page<FavoriteVO> resultPage = new Page<>(current, pageSize, pagedFavorites.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    public boolean isFavorite(Long goodsId) {
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        if (userId == null) {
            return false;
        }

        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getGoodsId, goodsId)
        );
        return count != null && count > 0;
    }

    @Override
    public long getFavoriteCount(Long goodsId) {
        String redisKey = FAVORITE_KEY_PREFIX + goodsId;
        try {
            Object cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                long val;
                if (cached instanceof Number) {
                    val = ((Number) cached).longValue();
                } else {
                    val = Long.parseLong(cached.toString().trim());
                }
                if (val >= 0) {
                    return val;
                }
            }
        } catch (Exception e) {
            log.warn("读取 Redis 收藏计数异常，回退至数据库查询: goodsId={}, error={}", goodsId, e.getMessage());
        }

        // Redis key 不存在、数据异常或为负数时：从数据库计算真实收藏数并回填 Redis
        return refreshFavoriteCount(goodsId);
    }

    @Override
    public long refreshFavoriteCount(Long goodsId) {
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, goodsId)
        );
        long total = (count != null && count >= 0) ? count : 0L;
        try {
            String redisKey = FAVORITE_KEY_PREFIX + goodsId;
            redisTemplate.opsForValue().set(redisKey, total);
        } catch (Exception e) {
            log.warn("回填 Redis 收藏计数失败: goodsId={}, error={}", goodsId, e.getMessage());
        }
        return total;
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
