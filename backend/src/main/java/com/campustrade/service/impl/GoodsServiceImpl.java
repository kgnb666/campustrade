package com.campustrade.service.impl;

import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.StudentVerifyStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.campustrade.common.constant.CreditRule;

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

    /** 浏览量刷盘分布式锁的持有时长（秒）：防止实例崩溃后锁无法释放；正常执行远小于该时长。 */
    private static final long VIEW_SYNC_LOCK_TTL_SECONDS = 120L;

    /** 仅当锁值仍为自己的令牌时才删除，保证"谁加锁谁解锁"。 */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

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
                        .eq(StudentVerify::getVerifyStatus, StudentVerifyStatus.SUCCESS.getCode())
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
                .status(GoodsStatus.ON_SALE.getCode())
                .location(dto.getLocation())
                .viewCount(0)
                .createdTime(now)
                .updatedTime(now)
                .build();

        goodsMapper.insert(goods);
        Long goodsId = goods.getId();

        // 4. 保存商品图片列表（单条多值 INSERT，避免"一张图一次往返"）
        List<GoodsImage> images = new ArrayList<>();
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            for (int i = 0; i < dto.getImages().size(); i++) {
                images.add(GoodsImage.builder()
                        .goodsId(goodsId)
                        .imageUrl(dto.getImages().get(i))
                        .sort(i)
                        .createdTime(now)
                        .build());
            }
        }
        if (!images.isEmpty()) {
            goodsImageMapper.insertBatch(images);
        }

        // 5. 保存商品标签列表（同上：一次往返写完所有标签）
        List<GoodsTag> tags = new ArrayList<>();
        if (dto.getTags() != null) {
            for (String tag : dto.getTags()) {
                if (StringUtils.hasText(tag)) {
                    tags.add(GoodsTag.builder()
                            .goodsId(goodsId)
                            .tagName(tag.trim())
                            .build());
                }
            }
        }
        if (!tags.isEmpty()) {
            goodsTagMapper.insertBatch(tags);
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
        wrapper.eq(Goods::getStatus, GoodsStatus.ON_SALE.getCode());

        // 关键词检索 (标题或描述)
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            String kw = SearchKeywordUtils.normalize(queryDTO.getKeyword());
            // 通配符转义后再交给 LIKE ... ESCAPE：用户输入的 % / _ / \ 只作字面匹配。
            // 这里用 MyBatis-Plus 的 apply + {0} 占位符（仍然生成 #{} 预编译参数），不拼接任何字符串字面量，
            // SQL 结构固定为 "(title LIKE ? ESCAPE '\' OR description LIKE ? ESCAPE '\')"。
            String likePattern = SearchKeywordUtils.escapeLikePattern(kw);
            if (likePattern != null) {
                String containsPattern = "%" + likePattern + "%";
                wrapper.and(w -> w.apply(
                        "(title LIKE {0} ESCAPE '\\' OR description LIKE {0} ESCAPE '\\')",
                        containsPattern));
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

        // searchCount 保持开启（不改为 false）：分页响应里的 total/pages 是既有接口契约的一部分，
        // 前端依赖 pages 判定 hasMore（见 frontend GoodsController.loadGoods），关闭会让列表无法翻页。
        // 因此这里选择"让 count 更快"而不是"不做 count"：
        //   * V11 为 goods 增加了把 status 放在首列的复合索引，count 与列表共用同一组前缀列；
        //     实测（200k 行实验库）count 可走 Index Only Scan：22 buffers / 1.0ms，
        //     而 bitmap heap scan 需 2611 buffers / 2.6ms、并行顺序扫描需 5882 buffers / 13ms。
        //     注意：默认 random_page_cost=4 会让规划器偏向顺序扫描路径，
        //     SSD 环境把该参数调到 1.1 左右时索引路径才会被自然选中（详见 V11 迁移注释与交付说明）。
        //   * MyBatis-Plus 默认 optimizeCountSql=true 会剥掉 ORDER BY 与无用的 select 列，
        //     count 语句本身不包含排序开销。
        // 若后续要支持"无限滚动到底"的场景，建议改为游标（keyset）分页：以 created_time + id 作为
        // 游标条件，既能继续走上面的复合索引，也能避免深 OFFSET 的线性开销。
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
                        .eq(StudentVerify::getVerifyStatus, StudentVerifyStatus.SUCCESS.getCode())
                        .last("LIMIT 1")
        );
        boolean isVerified = sellerVerify != null;
        String sellerSchoolName = (school != null) ? school.getSchoolName() : null;

        // 8. 卖家信用档案
        UserCredit sellerCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, goods.getSellerId())
        );
        int creditScore = (sellerCredit != null)
                ? sellerCredit.getCreditScore() : CreditRule.SCORE_DEFAULT;
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
        if (GoodsStatus.LOCKED.matches(goods.getStatus()) || GoodsStatus.SOLD.matches(goods.getStatus())) {
            throw new BusinessException(400, "商品处于交易中或已售出，禁止修改或删除");
        }

        // 定点更新：只写调用方真正提交的字段。
        // 旧实现是"把整行读出来 → 改若干字段 → updateById(实体)"，在 NOT_NULL 策略下会把读取时刻的
        // status / view_count / price 等全部非空字段一起写回，覆盖并发提交（例如下单锁货、浏览量同步）。
        LambdaUpdateWrapper<Goods> updateWrapper = new LambdaUpdateWrapper<Goods>()
                .eq(Goods::getId, id)
                // 条件前置：编辑期间若商品被并发锁单/售出，本次更新必须整体失败
                .notIn(Goods::getStatus, GoodsStatus.LOCKED.getCode(), GoodsStatus.SOLD.getCode())
                .set(Goods::getUpdatedTime, LocalDateTime.now());

        if (StringUtils.hasText(dto.getTitle())) {
            updateWrapper.set(Goods::getTitle, dto.getTitle());
        }
        if (dto.getDescription() != null) {
            updateWrapper.set(Goods::getDescription, dto.getDescription());
        }
        if (dto.getCategoryId() != null) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (category == null || category.getStatus() != 1) {
                throw new BusinessException(400, "指定的商品分类无效");
            }
            updateWrapper.set(Goods::getCategoryId, dto.getCategoryId());
        }
        if (dto.getPrice() != null) {
            updateWrapper.set(Goods::getPrice, dto.getPrice());
        }
        if (dto.getOriginalPrice() != null) {
            updateWrapper.set(Goods::getOriginalPrice, dto.getOriginalPrice());
        }
        if (StringUtils.hasText(dto.getConditionLevel())) {
            updateWrapper.set(Goods::getConditionLevel, dto.getConditionLevel());
        }
        if (dto.getLocation() != null) {
            updateWrapper.set(Goods::getLocation, dto.getLocation());
        }

        int affected = goodsMapper.update(null, updateWrapper);
        if (affected <= 0) {
            throw goodsStatusConflict(id, "修改");
        }

        // 如果传入了新的图片列表，重新替换（批量插入：一次往返）
        if (dto.getImages() != null) {
            goodsImageMapper.delete(new LambdaQueryWrapper<GoodsImage>().eq(GoodsImage::getGoodsId, id));
            List<GoodsImage> newImages = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < dto.getImages().size(); i++) {
                newImages.add(GoodsImage.builder()
                        .goodsId(id)
                        .imageUrl(dto.getImages().get(i))
                        .sort(i)
                        .createdTime(now)
                        .build());
            }
            if (!newImages.isEmpty()) {
                goodsImageMapper.insertBatch(newImages);
            }
        }

        // 如果传入了新的标签列表，重新替换（批量插入：一次往返）
        if (dto.getTags() != null) {
            goodsTagMapper.delete(new LambdaQueryWrapper<GoodsTag>().eq(GoodsTag::getGoodsId, id));
            List<GoodsTag> newTags = new ArrayList<>();
            for (String tag : dto.getTags()) {
                if (StringUtils.hasText(tag)) {
                    newTags.add(GoodsTag.builder()
                            .goodsId(id)
                            .tagName(tag.trim())
                            .build());
                }
            }
            if (!newTags.isEmpty()) {
                goodsTagMapper.insertBatch(newTags);
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
        if (GoodsStatus.LOCKED.matches(goods.getStatus()) || GoodsStatus.SOLD.matches(goods.getStatus())) {
            throw new BusinessException(400, "商品处于交易中或已售出，禁止修改或删除");
        }

        // 逻辑删除: 状态变更为 OFF_SHELF（定点更新 + 前置条件，避免整行回写覆盖并发状态）
        int affected = goodsMapper.updateStatusIfTradable(id, GoodsStatus.OFF_SHELF.getCode());
        if (affected <= 0) {
            throw goodsStatusConflict(id, "下架");
        }
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
        if (GoodsStatus.LOCKED.matches(goods.getStatus())) {
            throw new BusinessException(400, "商品处于交易锁定中，禁止变更上下架状态");
        }
        if (GoodsStatus.SOLD.matches(goods.getStatus())) {
            throw new BusinessException(400, "商品已售出，禁止变更状态");
        }

        // 入参只接受 ON_SALE / OFF_SHELF 两种跃迁目标，其余取值（含 DRAFT/LOCKED/SOLD）一律拒绝
        GoodsStatus requestedStatus = GoodsStatus.fromCode(status);
        if (requestedStatus != GoodsStatus.ON_SALE && requestedStatus != GoodsStatus.OFF_SHELF) {
            throw new BusinessException(400, "仅支持修改为 "
                    + GoodsStatus.ON_SALE.getCode() + " (上架) 或 "
                    + GoodsStatus.OFF_SHELF.getCode() + " (下架) 状态");
        }

        // 定点更新 + 前置条件：仅当商品既非交易中(LOCKED)也非已售出(SOLD)时才允许上下架
        String targetStatus = requestedStatus.getCode();
        int affected = goodsMapper.updateStatusIfTradable(id, targetStatus);
        if (affected <= 0) {
            throw goodsStatusConflict(id, "变更上下架状态");
        }
        log.info("用户 [{}] 修改商品 ID=[{}] 状态为 [{}]", userId, id, targetStatus);
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

    @Override
    public IPage<GoodsListVO> pageMyGoods(Integer page, Integer size) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }

        int current = (page != null && page > 0) ? page : 1;
        int pageSize = (size != null && size > 0) ? Math.min(size, 100) : 10;

        Page<Goods> pageParam = new Page<>(current, pageSize);
        IPage<Goods> goodsPage = goodsMapper.selectPage(
                pageParam,
                new LambdaQueryWrapper<Goods>()
                        .eq(Goods::getSellerId, userId)
                        .orderByDesc(Goods::getCreatedTime)
        );

        Page<GoodsListVO> resultPage = new Page<>(goodsPage.getCurrent(), goodsPage.getSize(), goodsPage.getTotal());
        resultPage.setRecords(convertToVOList(goodsPage.getRecords()));
        return resultPage;
    }

    /**
     * 状态条件更新未命中（受影响行数为 0）时，回读真实状态并给出<b>指向真实原因</b>的业务异常。
     *
     * <p>刻意不复用"商品已被锁定"这类笼统文案：0 行的真实原因可能是被并发下单锁定、已被售出、
     * 已被管理员下架、或记录已被删除，错误信息必须能直接回答"到底发生了什么"。</p>
     */
    private BusinessException goodsStatusConflict(Long goodsId, String action) {
        Goods latest = goodsMapper.selectById(goodsId);
        String currentStatus = (latest != null) ? String.valueOf(latest.getStatus()) : "记录已不存在";
        log.warn("商品状态条件更新未命中（0 行受影响）: goodsId={}, action={}, currentStatus={}",
                goodsId, action, currentStatus);
        return new BusinessException(409,
                String.format("商品状态在本次%s期间已被并发变更（当前状态: %s），请刷新后重试", action, currentStatus));
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
        // 多实例互斥：定时任务在集群每个节点上都会触发，若不加锁，两个实例可能同时消费同一批增量，
        // 造成 Redis 增量被重复扣减（浏览量丢失）。这里用 Redis SET NX PX 做分布式互斥，
        // 未抢到锁的实例直接让出本次执行，由持锁实例完成刷盘。
        String lockKey = RedisKeyConstants.goodsViewSyncLockKey();
        String lockToken = UUID.randomUUID().toString();
        if (!tryAcquireSyncLock(lockKey, lockToken)) {
            log.warn("未获取到浏览量刷盘分布式锁，跳过本次同步（其他实例正在执行）: lockKey={}", lockKey);
            return;
        }
        try {
            doSyncViewCounts();
        } finally {
            releaseSyncLock(lockKey, lockToken);
        }
    }

    /**
     * 浏览量增量落盘核心流程（已持有分布式锁）。
     *
     * <h2>失败可重入设计</h2>
     * 顺序严格为「先写库成功，再扣减 Redis 增量」：
     * <ol>
     *   <li>从脏集合 pop 出商品 ID（pop 本身是原子消费，同一 ID 不会被两个消费者同时拿到）；</li>
     *   <li>读取该商品的 Redis 增量 delta；</li>
     *   <li>用 {@code view_count = view_count + delta} 定点更新数据库；</li>
     *   <li><b>仅当第 3 步成功</b>才 DECRBY 扣减 Redis 增量；</li>
     *   <li>任何异常（DB 连接中断、锁等待超时、约束冲突等）都把商品 ID 放回脏集合并<b>保留增量</b>，
     *       下一轮同步会重新消费，因此增量既不丢失也不会重复计算。</li>
     * </ol>
     * 若扣减后 Redis 仍有剩余增量（说明刷盘期间又有新浏览累加），同样把 ID 放回脏集合，
     * 保证"最后一次增量"也有机会落盘，不需要等到用户下次访问才被顺带同步。
     */
    private void doSyncViewCounts() {
        final int batchSize = 100;
        // 防御性上限：避免脏集合被持续灌入时本方法长时间占用线程
        final int maxBatches = 50;
        int batchCount = 0;

        while (batchCount++ < maxBatches) {
            List<String> dirtyGoodsIds;
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
                if (!StringUtils.hasText(goodsIdStr)) {
                    continue;
                }
                syncSingleGoodsViewCount(goodsIdStr);
            }

            if (dirtyGoodsIds.size() < batchSize) {
                break;
            }
        }

        if (batchCount > maxBatches) {
            log.warn("浏览量刷盘达到单次批次数上限({}), 剩余脏商品将留待下一轮同步", maxBatches);
        }
    }

    /**
     * 单个商品的增量落盘：写库成功后才扣减 Redis 增量，失败则回灌脏集合并保留增量。
     */
    private void syncSingleGoodsViewCount(String goodsIdStr) {
        String viewKey = VIEW_KEY_PREFIX + goodsIdStr;
        Long goodsId;
        try {
            goodsId = Long.parseLong(goodsIdStr);
        } catch (NumberFormatException e) {
            log.warn("脏集合中存在非法的商品 ID，已丢弃: goodsIdStr={}", goodsIdStr);
            return;
        }

        int delta;
        try {
            String val = stringRedisTemplate.opsForValue().get(viewKey);
            if (val == null) {
                // 增量键已不存在（被清理或从未写入）：无需落盘，也无需回灌
                return;
            }
            delta = Integer.parseInt(val.trim());
        } catch (Exception e) {
            log.warn("读取 Redis 浏览量增量失败，商品 ID 放回脏集合等待下轮重试: goodsId={}, error={}", goodsIdStr, e.getMessage());
            markViewDirty(goodsIdStr);
            return;
        }

        if (delta <= 0) {
            // 增量已为 0 或异常负值：无需落盘
            return;
        }

        try {
            // 定点更新：只动 view_count 与 updated_time，绝不回写 status 等其它列
            int affected = goodsMapper.incrementViewCount(goodsId, delta);
            if (affected <= 0) {
                // 商品不存在（已被物理删除）：增量永远无法落盘，清理残留计数避免脏集合被永久反复消费
                log.warn("浏览量增量对应的商品不存在，已清理无效增量: goodsId={}, abandonedDelta={}", goodsId, delta);
                stringRedisTemplate.delete(viewKey);
                return;
            }

            // 写库成功后才扣减 Redis 增量：这一步失败也不会丢数据（增量仍留在 Redis，ID 仍在脏集合）
            Long remaining = stringRedisTemplate.opsForValue().decrement(viewKey, delta);
            if (remaining != null && remaining > 0) {
                // 刷盘期间又有新浏览累加：把 ID 放回脏集合，确保剩下的增量也能被落盘
                markViewDirty(goodsIdStr);
                log.debug("浏览量刷盘期间有新增量写入，商品 ID 已重新登记脏集合: goodsId={}, remainingDelta={}",
                        goodsId, remaining);
            }
            log.debug("商品浏览量增量落盘成功: goodsId={}, delta={}", goodsId, delta);
        } catch (Exception e) {
            // 写库失败：把商品 ID 放回脏集合、保留 Redis 增量，下一轮重试，增量不丢失
            markViewDirty(goodsIdStr);
            log.error("商品浏览量增量落盘失败，商品 ID 已放回脏集合并保留增量待重试: goodsId={}, delta={}",
                    goodsId, delta, e);
        }
    }

    /**
     * 把商品 ID 重新登记进脏集合（失败重试与剩余增量回灌共用）。
     * 登记失败只记录日志：Redis 不可用时用户访问详情页本身也无法累加，不会产生新的增量。
     */
    private void markViewDirty(String goodsIdStr) {
        try {
            stringRedisTemplate.opsForSet().add(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsIdStr);
        } catch (Exception e) {
            log.error("重新登记浏览量脏商品 ID 失败，该增量将在下次用户浏览该商品时才会被重新登记: goodsId={}", goodsIdStr, e);
        }
    }

    /**
     * 尝试获取刷盘分布式锁（SET key token NX PX ttl）。
     */
    private boolean tryAcquireSyncLock(String lockKey, String lockToken) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockToken, VIEW_SYNC_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.error("获取浏览量刷盘分布式锁失败（Redis 异常），本次放弃同步以避免多实例重复扣减: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 释放刷盘分布式锁：仅当锁值等于自己的令牌时删除（Lua 保证 get + del 原子），
     * 避免任务超时后误删其他实例刚抢到的锁。
     */
    private void releaseSyncLock(String lockKey, String lockToken) {
        try {
            stringRedisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    lockToken
            );
        } catch (Exception e) {
            log.warn("释放浏览量刷盘分布式锁失败（将由 TTL 自动过期）: lockKey={}, error={}", lockKey, e.getMessage());
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

        // 批量读取 Redis 浏览量增量（MGET 一次往返，替代"每个商品一次 GET"的 N+1）
        Map<Long, Integer> viewDeltaMap = loadViewDeltas(goodsIds);

        return goodsList.stream().map(goods -> {
            // 计算实时浏览量 = 库内快照 + Redis 未落盘增量
            int delta = viewDeltaMap.getOrDefault(goods.getId(), 0);
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

    /**
     * 批量读取一组商品的 Redis 浏览量增量。
     *
     * <p>历史实现对每个商品单独 {@code GET goods:view:{id}}，一页 10 条就是 10 次往返；
     * 改为一次 {@code MGET} 后往返次数恒为 1（与列表长度无关）。</p>
     *
     * <p>Redis 不可用时与商品详情页保持同样的"优雅降级"策略：只记录告警并返回空增量，
     * 列表仍以数据库快照正常展示浏览量，而不是把整个列表接口打成 500。</p>
     */
    private Map<Long, Integer> loadViewDeltas(List<Long> goodsIds) {
        if (goodsIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = goodsIds.stream().map(gid -> VIEW_KEY_PREFIX + gid).collect(Collectors.toList());
        List<String> values;
        try {
            values = stringRedisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            log.warn("批量读取商品浏览量缓存失败，列表降级为仅展示数据库计数: error={}", e.getMessage());
            return Collections.emptyMap();
        }
        if (values == null) {
            return Collections.emptyMap();
        }

        Map<Long, Integer> deltaMap = new HashMap<>();
        for (int i = 0; i < goodsIds.size(); i++) {
            String val = (i < values.size()) ? values.get(i) : null;
            if (val == null) {
                continue;
            }
            try {
                int delta = Integer.parseInt(val.trim());
                if (delta != 0) {
                    deltaMap.put(goodsIds.get(i), delta);
                }
            } catch (NumberFormatException ignored) {
                // 脏值按 0 处理：不因单个异常值影响整页展示
            }
        }
        return deltaMap;
    }
}
