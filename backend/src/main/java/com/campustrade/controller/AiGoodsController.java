package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.dto.AiCategoryDTO;
import com.campustrade.dto.AiDescriptionDTO;
import com.campustrade.dto.AiPriceDTO;
import com.campustrade.service.ai.AiGoodsService;
import com.campustrade.vo.AiCategoryVO;
import com.campustrade.vo.AiDescriptionVO;
import com.campustrade.vo.AiPriceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 商品助手控制器
 */
@RestController
@RequestMapping("/ai/goods")
@RequiredArgsConstructor
public class AiGoodsController {

    private final AiGoodsService aiGoodsService;

    /**
     * AI 生成/润色商品描述
     */
    @PostMapping("/description")
    public Result<AiDescriptionVO> generateDescription(@Valid @RequestBody AiDescriptionDTO dto) {
        AiDescriptionVO vo = aiGoodsService.generateDescription(dto);
        return Result.success("AI 生成商品描述成功", vo);
    }

    /**
     * AI 智能分类推荐
     */
    @PostMapping("/category")
    public Result<AiCategoryVO> recommendCategory(@Valid @RequestBody AiCategoryDTO dto) {
        AiCategoryVO vo = aiGoodsService.recommendCategory(dto);
        return Result.success("AI 分类推荐成功", vo);
    }

    /**
     * AI 价格辅助建议
     */
    @PostMapping("/price")
    public Result<AiPriceVO> suggestPrice(@Valid @RequestBody AiPriceDTO dto) {
        AiPriceVO vo = aiGoodsService.suggestPrice(dto);
        return Result.success("AI 价格评估成功", vo);
    }
}
