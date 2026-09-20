package com.campustrade.service.ai;

import com.campustrade.dto.AiCategoryDTO;
import com.campustrade.dto.AiDescriptionDTO;
import com.campustrade.dto.AiPriceDTO;
import com.campustrade.vo.AiCategoryVO;
import com.campustrade.vo.AiDescriptionVO;
import com.campustrade.vo.AiPriceVO;

/**
 * AI 商品辅助服务接口
 */
public interface AiGoodsService {

    /**
     * AI 智能润色与生成商品描述
     *
     * @param dto 商品信息
     * @return 结构化描述与标签
     */
    AiDescriptionVO generateDescription(AiDescriptionDTO dto);

    /**
     * AI 智能商品分类推荐
     *
     * @param dto 标题与描述
     * @return 推荐分类与置信度
     */
    AiCategoryVO recommendCategory(AiCategoryDTO dto);

    /**
     * AI 二手闲置估价辅助建议
     *
     * @param dto 标题、原价、成色等
     * @return 建议估价区间与分析
     */
    AiPriceVO suggestPrice(AiPriceDTO dto);
}
