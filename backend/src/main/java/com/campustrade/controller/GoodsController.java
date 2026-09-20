package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.dto.CreateGoodsDTO;
import com.campustrade.dto.GoodsQueryDTO;
import com.campustrade.dto.UpdateGoodsDTO;
import com.campustrade.dto.UpdateGoodsStatusDTO;
import com.campustrade.entity.User;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.GoodsService;
import com.campustrade.service.SearchHistoryService;
import com.campustrade.service.UserService;
import com.campustrade.vo.GoodsDetailVO;
import com.campustrade.vo.GoodsListVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 商品中心控制器
 */
@RestController
@RequestMapping({"/goods", "/api/goods"})
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;
    private final SearchHistoryService searchHistoryService;
    private final UserService userService;

    /**
     * 发布商品
     */
    @PostMapping
    public Result<Long> createGoods(@Valid @RequestBody CreateGoodsDTO dto) {
        Long goodsId = goodsService.createGoods(dto);
        return Result.success("商品发布成功", goodsId);
    }

    /**
     * 分页查询商品列表 (公共公开，支持搜索筛选)
     */
    @GetMapping("/list")
    public Result<IPage<GoodsListVO>> pageGoods(GoodsQueryDTO queryDTO) {
        IPage<GoodsListVO> page = goodsService.pageGoods(queryDTO);
        return Result.success("获取商品列表成功", page);
    }

    /**
     * 增强搜索商品 (自动记录用户搜索历史与更新全站热搜权重)
     */
    @GetMapping("/search")
    public Result<IPage<GoodsListVO>> searchGoods(GoodsQueryDTO queryDTO) {
        IPage<GoodsListVO> page = goodsService.searchGoods(queryDTO);
        return Result.success("搜索商品成功", page);
    }

    /**
     * 获取全站热门搜索词排行榜
     */
    @GetMapping("/search/hot")
    public Result<List<String>> getHotSearches(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        List<String> hotKeywords = searchHistoryService.getHotSearches(limit);
        return Result.success("获取热门搜索词成功", hotKeywords);
    }

    /**
     * 获取当前登录用户的个人搜索历史
     */
    @GetMapping("/search/history")
    public Result<List<String>> getUserSearchHistory(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        if (userId == null) {
            return Result.success("未登录", Collections.emptyList());
        }
        List<String> history = searchHistoryService.getUserRecentSearches(userId, limit);
        return Result.success("获取搜索历史成功", history);
    }

    /**
     * 获取商品详情并累计浏览量
     */
    @GetMapping("/{id}")
    public Result<GoodsDetailVO> getGoodsDetail(@PathVariable("id") Long id) {
        GoodsDetailVO detail = goodsService.getGoodsDetail(id);
        return Result.success("获取商品详情成功", detail);
    }

    /**
     * 修改商品信息 (仅限发布者本人)
     */
    @PutMapping("/{id}")
    public Result<Void> updateGoods(@PathVariable("id") Long id, @Valid @RequestBody UpdateGoodsDTO dto) {
        goodsService.updateGoods(id, dto);
        return Result.success("商品修改成功", null);
    }

    /**
     * 逻辑删除商品 (流转为 OFF_SHELF, 仅限发布者本人)
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteGoods(@PathVariable("id") Long id) {
        goodsService.deleteGoods(id);
        return Result.success("商品已成功下架删除", null);
    }

    /**
     * 修改商品状态 (仅限发布者本人)
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable("id") Long id, @Valid @RequestBody UpdateGoodsStatusDTO dto) {
        goodsService.updateGoodsStatus(id, dto.getStatus());
        return Result.success("商品状态修改成功", null);
    }

    /**
     * 获取当前登录用户发布的全部商品
     */
    @GetMapping("/my")
    public Result<List<GoodsListVO>> listMyGoods() {
        List<GoodsListVO> list = goodsService.listMyGoods();
        return Result.success("获取我的商品成功", list);
    }

    /**
     * 手动触发同步 Redis 浏览量缓存至数据库 (仅限管理员调用)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/sync-views")
    public Result<Void> syncViewCounts() {
        goodsService.syncViewCounts();
        return Result.success("浏览量缓存同步成功", null);
    }
}
