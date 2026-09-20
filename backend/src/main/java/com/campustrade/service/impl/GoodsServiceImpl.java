package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.util.SearchKeywordUtils;
import com.campustrade.dto.CreateGoodsDTO;
import com.campustrade.dto.GoodsQueryDTO;
import com.campustrade.dto.UpdateGoodsDTO;
import com.campustrade.entity.*;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.*;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.BrowseHistoryService;
import com.campustrade.service.FavoriteService;
import com.campustrade.service.GoodsService;
import com.campustrade.service.SearchHistoryService;
import com.campustrade.vo.GoodsDetailVO;
import com.campustrade.vo.GoodsListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品核心服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsServiceImpl implements GoodsService {

    private final GoodsMapper goodsMapper;
    private final GoodsImageMapper goodsImageMapper;
    private final GoodsTagMapper goodsTagMapper;
    private final CategoryMapper categoryMapper;
    private final CampusSchoolMapper campusSchoolMapper;
    private final UserMapper userMapper;
    private final StudentVerifyMapper studentVerifyMapper;
    private final UserCreditMapper userCreditMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final BrowseHistoryService browseHistoryService;
    private final SearchHistoryService searchHistoryService;
    private final FavoriteService favoriteService;

    private static final String VIEW_KEY_PREFIX = RedisKeyConstants.GOODS_VIEW_PREFIX;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createGoods(CreateGoodsDTO dto) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录后再发布商品");
        }

        // 1. 获取当前用户校园认证信息，强制绑定学校
        StudentVerify studentVerify = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, userId)
                        .eq(StudentVerify::getVerifyStatus, "SUCCESS")
                        .last("LIMIT 1")
        );
        if (studentVerify == null) {
            throw new BusinessException(400, "发布二手商品前请先完成校园身份认证");
        }
        Long schoolId = studentVerify.getSchoolId();

        // 2. 校验分类有效性
        Category category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null || category.getStatus() != 1) {
            throw new BusinessException(400, "所选商品分类无效或已停用");
        }

        // 3. 构建商品实体并持久化
        LocalDateTime now = LocalDateTime.now();
        Goods goods = Goods.builder()
                .sellerId(userId)
                .schoolId(schoolId)
                .categoryId(dto.getCategoryId())
                .title(dto.getTitle())
                .description(dto.getDescription())
                .price(dto.getPrice())
                .originalPrice(dto.getOriginalPrice())
                .conditionLevel(dto.getConditionLevel())
                .status("ON_SALE")
                .location(dto.getLocation())
                .viewCount(0)
                .createdTime(now)
                .updatedTime(now)
                .build();

        goodsMapper.insert(goods);
        Long goodsId = goods.getId();

        // 4. 保存商品图片列表
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            for (int i = 0; i < dto.getImages().size(); i++) {
                GoodsImage image = GoodsImage.builder()
                        .goodsId(goodsId)
                        .imageUrl(dto.getImages().get(i))
                        .sort(i)
                        .createdTime(now)
                        .build();
                goodsImageMapper.insert(image);
            }
        }

        // 5. 保存商品标签列表
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            for (String tag : dto.getTags()) {
                if (StringUtils.hasText(tag)) {
                    GoodsTag goodsTag = GoodsTag.builder()
                            .goodsId(goodsId)
                            .tagName(tag.trim())
                            .build();
                    goodsTagMapper.insert(goodsTag);
                }
            }
        }

        log.info("用户 [{}] 成功发布商品 ID=[{}], 标题=[{}]", userId, goodsId, goods.getTitle());
        return goodsId;
    }

    @Override
    public IPage<GoodsListVO> pageGoods(GoodsQueryDTO queryDTO) {
        int current = (queryDTO.getPage() != null && queryDTO.getPage() > 0) ? queryDTO.getPage() : 1;
        int size = (queryDTO.getSize() != null && queryDTO.getSize() > 0) ? Math.min(queryDTO.getSize(), 100) : 10;

        Page<Goods> page = new Page<>(current, size);
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();

        // 默认公开列表只展示在售商品
        wrapper.eq(Goods::getStatus, "ON_SALE");

        // 关键词检索 (标题或描述)
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            String kw = SearchKeywordUtils.normalize(queryDTO.getKeyword());
            if (kw != null) {
                final String searchKw = kw;
                wrapper.and(w -> w.like(Goods::getTitle, searchKw).or().like(Goods::getDescription, searchKw));
            }
        }

        // 分类检索 (支持选择一级分类时联同包含其二级分类)
        if (queryDTO.getCategoryId() != null) {
            List<Category> children = categoryMapper.selectList(
                    new LambdaQueryWrapper<Category>().eq(Category::getParentId, queryDTO.getCategoryId())
            );
            if (children != null && !children.isEmpty()) {
                List<Long> categoryIds = children.stream().map(Category::getId).collect(Collectors.toList());
                categoryIds.add(queryDTO.getCategoryId());
                wrapper.in(Goods::getCategoryId, categoryIds);
            } else {
                wrapper.eq(Goods::getCategoryId, queryDTO.getCategoryId());
            }
        }

        // 学校筛选
        if (queryDTO.getSchoolId() != null) {
            wrapper.eq(Goods::getSchoolId, queryDTO.getSchoolId());
        }

        // 价格区间
        if (queryDTO.getMinPrice() != null) {
            wrapper.ge(Goods::getPrice, queryDTO.getMinPrice());
        }
        if (queryDTO.getMaxPrice() != null) {
            wrapper.le(Goods::getPrice, queryDTO.getMaxPrice());
        }

        // 成色筛选
        if (StringUtils.hasText(queryDTO.getConditionLevel())) {
            wrapper.eq(Goods::getConditionLevel, queryDTO.getConditionLevel().trim());
        }

        // 排序规则: 最新发布优先
        wrapper.orderByDesc(Goods::getCreatedTime);

        IPage<Goods> goodsPage = goodsMapper.selectPage(page, wrapper);

        // 组装 VO
        List<GoodsListVO> voList = convertToVOList(goodsPage.getRecords());

        Page<GoodsListVO> resultPage = new Page<>(goodsPage.getCurrent(), goodsPage.getSize(), goodsPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    public IPage<GoodsListVO> searchGoods(GoodsQueryDTO queryDTO) {
        String normalized = SearchKeywordUtils.normalize(queryDTO.getKeyword());
        queryDTO.setKeyword(normalized);
        if (normalized != null) {
            Long currentUserId = getCurrentUserIdOrNull();
            searchHistoryService.recordSearch(currentUserId, normalized);
        }
        return pageGoods(queryDTO);
    }

    @Override
    public GoodsDetailVO getGoodsDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(404, "商品不存在或已被删除");
        }
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(404, "商品不存在或已被删除");
        }

        // 自动记录浏览足迹 (若用户已登录)
        Long currentUserId = getCurrentUserIdOrNull();
        if (currentUserId != null) {
            try {
                browseHistoryService.recordBrowse(currentUserId, id);
            } catch (Exception e) {
                log.warn("记录用户 [{}] 浏览商品 [{}] 失败: {}", currentUserId, id, e.getMessage());
            }
        }

        // 1. Redis 浏览量累加缓存 (INCR，带故障降级保底)
        String viewKey = VIEW_KEY_PREFIX + id;
        Long redisViewDelta = null;
        try {
            redisViewDelta = stringRedisTemplate.opsForValue().increment(viewKey);
            // 顺带将该 goodsId 加入脏数据 Set，消除 keys(*) 全库扫描隐患
            stringRedisTemplate.opsForSet().add(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, id.toString());
        } catch (Exception e) {
            log.warn("累加商品 [{}] Redis 浏览量缓存失败，优雅降级为 DB 计数: {}", id, e.getMessage());
        }
        int totalViews = (goods.getViewCount() != null ? goods.getViewCount() : 0)
                + (redisViewDelta != null ? redisViewDelta.intValue() : 0);

        // 2. 收藏状态与收藏数
        boolean isFav = (currentUserId != null) && favoriteService.isFavorite(id);
        long favCount = favoriteService.getFavoriteCount(id);

        // 3. 查询图片列表 (按 sort 升序)
        List<GoodsImage> images = goodsImageMapper.selectList(
                new LambdaQueryWrapper<GoodsImage>()
                        .eq(GoodsImage::getGoodsId, id)
                        .orderByAsc(GoodsImage::getSort)
        );
        List<String> imageUrls = (images != null)
                ? images.stream().map(GoodsImage::getImageUrl).collect(Collectors.toList())
                : new ArrayList<>();

        // 4. 查询标签列表
        List<GoodsTag> tags = goodsTagMapper.selectList(
                new LambdaQueryWrapper<GoodsTag>().eq(GoodsTag::getGoodsId, id)
        );
        List<String> tagNames = (tags != null)
                ? tags.stream().map(GoodsTag::getTagName).collect(Collectors.toList())
                : new ArrayList<>();

        // 5. 查询分类和学校名称
        Category category = categoryMapper.selectById(goods.getCategoryId());
        CampusSchool school = campusSchoolMapper.selectById(goods.getSchoolId());

        // 6. 查询卖家信息
        User seller = userMapper.selectById(goods.getSellerId());
        String sellerUsername = (seller != null) ? seller.getUsername() : "未知用户";
        String sellerNickname = (seller != null && StringUtils.hasText(seller.getNickname())) ? seller.getNickname() : sellerUsername;
        String sellerAvatar = (seller != null) ? seller.getAvatar() : null;

        // 7. 卖家校园认证信息
        StudentVerify sellerVerify = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, goods.getSellerId())
                        .eq(StudentVerify::getVerifyStatus, "SUCCESS")
                        .last("LIMIT 1")
        );
        boolean isVerified = sellerVerify != null;
        String sellerSchoolName = (school != null) ? school.getSchoolName() : null;

        // 8. 卖家信用档案
        UserCredit sellerCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, goods.getSellerId())
        );
        int creditScore = (sellerCredit != null) ? sellerCredit.getCreditScore() : 100;
        int tradeCount = (sellerCredit != null) ? sellerCredit.getTradeCount() : 0;
        int goodReviewCount = (sellerCredit != null) ? sellerCredit.getGoodReviewCount() : 0;

        return GoodsDetailVO.builder()
                .id(goods.getId())
                .sellerId(goods.getSellerId())
                .schoolId(goods.getSchoolId())
                .schoolName(sellerSchoolName)
                .categoryId(goods.getCategoryId())
                .categoryName(category != null ? category.getName() : null)
                .title(goods.getTitle())
                .description(goods.getDescription())
                .price(goods.getPrice())
                .originalPrice(goods.getOriginalPrice())
                .conditionLevel(goods.getConditionLevel())
                .status(goods.getStatus())
                .location(goods.getLocation())
                .viewCount(totalViews)
                .createdTime(goods.getCreatedTime())
                .updatedTime(goods.getUpdatedTime())
                .images(imageUrls)
                .tags(tagNames)
                .sellerUsername(sellerUsername)
                .sellerNickname(sellerNickname)
                .sellerAvatar(sellerAvatar)
                .sellerVerified(isVerified)
                .sellerSchoolName(sellerSchoolName)
                .sellerCreditScore(creditScore)
                .sellerTradeCount(tradeCount)
                .sellerGoodReviewCount(goodReviewCount)
                .favoriteCount(favCount)
                .isFavorite(isFav)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGoods(Long id, UpdateGoodsDTO dto) {
        Long userId = getCurrentUserId();
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(404, "商品不存在");
        }

        // 权限校验: 只能修改本人的商品
        if (!goods.getSellerId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权修改他人的商品");
        }

        // 状态守卫: 交易中(LOCKED)或已售出(SOLD)商品禁止修改
        if ("LOCKED".equalsIgnoreCase(goods.getStatus()) || "SOLD".equalsIgnoreCase(goods.getStatus())) {
            throw new BusinessException(400, "商品处于交易中或已售出，禁止修改或删除");
        }

        // 更新基础属性
        if (StringUtils.hasText(dto.getTitle())) {
            goods.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            goods.setDescription(dto.getDescription());
        }
        if (dto.getCategoryId() != null) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (category == null || category.getStatus() != 1) {
                throw new BusinessException(400, "指定的商品分类无效");
            }
            goods.setCategoryId(dto.getCategoryId());
        }
        if (dto.getPrice() != null) {
            goods.setPrice(dto.getPrice());
        }
        if (dto.getOriginalPrice() != null) {
            goods.setOriginalPrice(dto.getOriginalPrice());
        }
        if (StringUtils.hasText(dto.getConditionLevel())) {
            goods.setConditionLevel(dto.getConditionLevel());
        }
        if (dto.getLocation() != null) {
            goods.setLocation(dto.getLocation());
        }

        goods.setUpdatedTime(LocalDateTime.now());
        goodsMapper.updateById(goods);

        // 如果传入了新的图片列表，重新替换
        if (dto.getImages() != null) {
            goodsImageMapper.delete(new LambdaQueryWrapper<GoodsImage>().eq(GoodsImage::getGoodsId, id));
            for (int i = 0; i < dto.getImages().size(); i++) {
                GoodsImage image = GoodsImage.builder()
                        .goodsId(id)
                        .imageUrl(dto.getImages().get(i))
                        .sort(i)
                        .createdTime(LocalDateTime.now())
                        .build();
                goodsImageMapper.insert(image);
            }
        }

        // 如果传入了新的标签列表，重新替换
        if (dto.getTags() != null) {
            goodsTagMapper.delete(new LambdaQueryWrapper<GoodsTag>().eq(GoodsTag::getGoodsId, id));
            for (String tag : dto.getTags()) {
                if (StringUtils.hasText(tag)) {
                    GoodsTag goodsTag = GoodsTag.builder()
                            .goodsId(id)
                            .tagName(tag.trim())
                            .build();
                    goodsTagMapper.insert(goodsTag);
                }
            }
        }

        log.info("用户 [{}] 修改商品 ID=[{}] 成功", userId, id);
    }

    @Override
    public void deleteGoods(Long id) {
        Long userId = getCurrentUserId();
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(404, "商品不存在");
        }

        // 权限校验: 只能下架/删除自己的商品
        if (!goods.getSellerId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权删除他人的商品");
        }

        // 状态守卫: 交易中(LOCKED)或已售出(SOLD)商品禁止删除
        if ("LOCKED".equalsIgnoreCase(goods.getStatus()) || "SOLD".equalsIgnoreCase(goods.getStatus())) {
            throw new BusinessException(400, "商品处于交易中或已售出，禁止修改或删除");
        }

        // 逻辑删除: 状态变更为 OFF_SHELF
        goods.setStatus("OFF_SHELF");
        goods.setUpdatedTime(LocalDateTime.now());
        goodsMapper.updateById(goods);
        log.info("用户 [{}] 逻辑删除商品 ID=[{}]", userId, id);
    }

    @Override
    public void updateGoodsStatus(Long id, String status) {
        Long userId = getCurrentUserId();
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(404, "商品不存在");
        }

        // 权限校验: 只能修改本人的商品
        if (!goods.getSellerId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权修改他人的商品状态");
        }

        // 状态守卫: 交易中(LOCKED)禁止变更状态，已售出(SOLD)禁止变更状态
        if ("LOCKED".equalsIgnoreCase(goods.getStatus())) {
            throw new BusinessException(400, "商品处于交易锁定中，禁止变更上下架状态");
        }
        if ("SOLD".equalsIgnoreCase(goods.getStatus())) {
            throw new BusinessException(400, "商品已售出，禁止变更状态");
        }

        if (!"ON_SALE".equalsIgnoreCase(status) && !"OFF_SHELF".equalsIgnoreCase(status)) {
            throw new BusinessException(400, "仅支持修改为 ON_SALE (上架) 或 OFF_SHELF (下架) 状态");
        }

        goods.setStatus(status.toUpperCase());
        goods.setUpdatedTime(LocalDateTime.now());
        goodsMapper.updateById(goods);
        log.info("用户 [{}] 修改商品 ID=[{}] 状态为 [{}]", userId, id, goods.getStatus());
    }

    @Override
    public List<GoodsListVO> listMyGoods() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }

        List<Goods> myGoods = goodsMapper.selectList(
                new LambdaQueryWrapper<Goods>()
                        .eq(Goods::getSellerId, userId)
                        .orderByDesc(Goods::getCreatedTime)
        );

        return convertToVOList(myGoods);
    }

    private Long getCurrentUserId() {
        String username = SecurityUtils.getCurrentUsername();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(401, "当前登录用户不存在或已注销");
        }
        return user.getId();
    }

    private Long getCurrentUserIdOrNull() {
        String username = SecurityUtils.getCurrentUsernameOrNull();
        if (username == null) {
            return null;
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        return user != null ? user.getId() : null;
    }

    @Override
    public void syncViewCounts() {
        // 采用 Set pop 批量消费脏商品 ID，保证 O(1) 批量弹出，彻底杜绝 keys(*) 阻塞主线程
        final int batchSize = 100;
        while (true) {
            List<String> dirtyGoodsIds = null;
            try {
                dirtyGoodsIds = stringRedisTemplate.opsForSet().pop(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, batchSize);
            } catch (Exception e) {
                log.warn("从 Redis Set 弹出待同步商品 ID 异常: {}", e.getMessage());
                break;
            }

            if (dirtyGoodsIds == null || dirtyGoodsIds.isEmpty()) {
                break;
            }

            for (String goodsIdStr : dirtyGoodsIds) {
                if (!StringUtils.hasText(goodsIdStr)) continue;
                String key = VIEW_KEY_PREFIX + goodsIdStr;
                try {
                    Long goodsId = Long.parseLong(goodsIdStr);
                    String val = stringRedisTemplate.opsForValue().get(key);
                    if (val != null) {
                        int delta = Integer.parseInt(val);
                        if (delta > 0) {
                            Goods goods = goodsMapper.selectById(goodsId);
                            if (goods != null) {
                                int currentViews = goods.getViewCount() != null ? goods.getViewCount() : 0;
                                goods.setViewCount(currentViews + delta);
                                goodsMapper.updateById(goods);
                                // 扣减已持久化的 delta
                                stringRedisTemplate.opsForValue().decrement(key, delta);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("同步商品浏览量缓存失败: goodsId={}", goodsIdStr, e);
                }
            }

            if (dirtyGoodsIds.size() < batchSize) {
                break;
            }
        }
    }

    private List<GoodsListVO> convertToVOList(List<Goods> goodsList) {
        if (goodsList == null || goodsList.isEmpty()) {
            return new ArrayList<>();
        }

        // 收集 schoolIds 与 categoryIds 批量检索
        Set<Long> schoolIds = goodsList.stream().map(Goods::getSchoolId).collect(Collectors.toSet());
        Set<Long> categoryIds = goodsList.stream().map(Goods::getCategoryId).collect(Collectors.toSet());
        List<Long> goodsIds = goodsList.stream().map(Goods::getId).collect(Collectors.toList());

        Map<Long, String> schoolMap = campusSchoolMapper.selectBatchIds(schoolIds).stream()
                .collect(Collectors.toMap(CampusSchool::getId, CampusSchool::getSchoolName));

        Map<Long, String> categoryMap = categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        // 批量查询主图 (每个商品 sort 最小的第一张图)
        List<GoodsImage> images = goodsImageMapper.selectList(
                new LambdaQueryWrapper<GoodsImage>()
                        .in(GoodsImage::getGoodsId, goodsIds)
                        .orderByAsc(GoodsImage::getSort)
        );
        Map<Long, String> coverImageMap = new HashMap<>();
        if (images != null) {
            for (GoodsImage img : images) {
                coverImageMap.putIfAbsent(img.getGoodsId(), img.getImageUrl());
            }
        }

        return goodsList.stream().map(goods -> {
            // 计算实时浏览量
            String val = stringRedisTemplate.opsForValue().get(VIEW_KEY_PREFIX + goods.getId());
            int delta = 0;
            if (val != null) {
                try {
                    delta = Integer.parseInt(val);
                } catch (Exception ignored) {
                }
            }
            int totalViews = (goods.getViewCount() != null ? goods.getViewCount() : 0) + delta;

            return GoodsListVO.builder()
                    .id(goods.getId())
                    .sellerId(goods.getSellerId())
                    .schoolId(goods.getSchoolId())
                    .schoolName(schoolMap.getOrDefault(goods.getSchoolId(), "未知高校"))
                    .categoryId(goods.getCategoryId())
                    .categoryName(categoryMap.getOrDefault(goods.getCategoryId(), "其他"))
                    .title(goods.getTitle())
                    .coverImage(coverImageMap.get(goods.getId()))
                    .price(goods.getPrice())
                    .originalPrice(goods.getOriginalPrice())
                    .conditionLevel(goods.getConditionLevel())
                    .status(goods.getStatus())
                    .location(goods.getLocation())
                    .viewCount(totalViews)
                    .createdTime(goods.getCreatedTime())
                    .build();
        }).collect(Collectors.toList());
    }
}
