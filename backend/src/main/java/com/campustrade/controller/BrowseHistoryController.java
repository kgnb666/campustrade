package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.service.BrowseHistoryService;
import com.campustrade.vo.BrowseHistoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览历史控制器
 */
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class BrowseHistoryController {

    private final BrowseHistoryService browseHistoryService;

    /**
     * 分页查询当前用户的浏览足迹 (倒序排序)
     */
    @GetMapping("/list")
    public Result<IPage<BrowseHistoryVO>> pageHistory(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        IPage<BrowseHistoryVO> result = browseHistoryService.pageHistory(page, size);
        return Result.success("获取浏览历史成功", result);
    }
}
