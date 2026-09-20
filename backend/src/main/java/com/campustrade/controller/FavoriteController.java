package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.service.FavoriteService;
import com.campustrade.vo.FavoriteVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 商品收藏控制器
 */
@RestController
@RequestMapping("/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 收藏商品
     */
    @PostMapping("/{goodsId}")
    public Result<Void> addFavorite(@PathVariable("goodsId") Long goodsId) {
        favoriteService.addFavorite(goodsId);
        return Result.success("收藏成功", null);
    }

    /**
     * 取消收藏
     */
    @DeleteMapping("/{goodsId}")
    public Result<Void> removeFavorite(@PathVariable("goodsId") Long goodsId) {
        favoriteService.removeFavorite(goodsId);
        return Result.success("已取消收藏", null);
    }

    /**
     * 分页查询当前用户收藏列表
     */
    @GetMapping("/list")
    public Result<IPage<FavoriteVO>> pageFavorites(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        IPage<FavoriteVO> result = favoriteService.pageFavorites(page, size);
        return Result.success("获取收藏列表成功", result);
    }

    /**
     * 检查当前用户是否已收藏指定商品
     */
    @GetMapping("/check/{goodsId}")
    public Result<Boolean> checkFavorite(@PathVariable("goodsId") Long goodsId) {
        boolean isFav = favoriteService.isFavorite(goodsId);
        return Result.success("查询成功", isFav);
    }
}
