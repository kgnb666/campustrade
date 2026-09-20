package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.dto.AiCategoryDTO;
import com.campustrade.dto.AiDescriptionDTO;
import com.campustrade.dto.AiPriceDTO;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.ai.AiGoodsService;
import com.campustrade.service.ai.AiQuotaGuard;
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
 *
 * <p>三个接口都要求已登录，并在进入业务逻辑前先过 {@link AiQuotaGuard} 的按 userId 配额
 * （分钟频控 + 日配额）：AI 调用是直接产生费用的外部依赖，必须有确定上界。</p>
 */
@RestController
@RequestMapping("/ai/goods")
@RequiredArgsConstructor
public class AiGoodsController {

    private final AiGoodsService aiGoodsService;
    private final AiQuotaGuard aiQuotaGuard;

    /**
     * AI 生成/润色商品描述
     */
    @PostMapping("/description")
    public Result<AiDescriptionVO> generateDescription(@Valid @RequestBody AiDescriptionDTO dto) {
        aiQuotaGuard.enforce(SecurityUtils.getCurrentUserId());
        AiDescriptionVO vo = aiGoodsService.generateDescription(dto);
        return Result.success("AI 生成商品描述成功", vo);
    }

    /**
     * AI 智能分类推荐
     */
    @PostMapping("/category")
    public Result<AiCategoryVO> recommendCategory(@Valid @RequestBody AiCategoryDTO dto) {
        aiQuotaGuard.enforce(SecurityUtils.getCurrentUserId());
        AiCategoryVO vo = aiGoodsService.recommendCategory(dto);
        return Result.success("AI 分类推荐成功", vo);
    }

    /**
     * AI 价格辅助建议
     */
    @PostMapping("/price")
    public Result<AiPriceVO> suggestPrice(@Valid @RequestBody AiPriceDTO dto) {
        aiQuotaGuard.enforce(SecurityUtils.getCurrentUserId());
        AiPriceVO vo = aiGoodsService.suggestPrice(dto);
        return Result.success("AI 价格评估成功", vo);
    }
}
