package com.campustrade.service.ai.impl;

import com.campustrade.dto.AiCategoryDTO;
import com.campustrade.dto.AiDescriptionDTO;
import com.campustrade.dto.AiPriceDTO;
import com.campustrade.entity.Category;
import com.campustrade.mapper.CategoryMapper;
import com.campustrade.service.ai.AiGoodsService;
import com.campustrade.service.ai.AiPromptConstants;
import com.campustrade.service.ai.DeepSeekClient;
import com.campustrade.vo.AiCategoryVO;
import com.campustrade.vo.AiDescriptionVO;
import com.campustrade.vo.AiPriceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 商品辅助业务实现类
 * 具备 DeepSeek 接口交互与本地降级兜底逻辑
 * 加固 Prompt Injection 隔离、严格输出 Schema 校验与业务逻辑完整性防线
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGoodsServiceImpl implements AiGoodsService {

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final CategoryMapper categoryMapper;

    /**
     * 单品价格合理性上限 (1,000,000.00 元)
     */
    private static final BigDecimal MAX_PRICE_CEILING = new BigDecimal("1000000.00");

    @Override
    public AiDescriptionVO generateDescription(AiDescriptionDTO dto) {
        String title = sanitizeInput(dto.getTitle());
        String condition = StringUtils.hasText(dto.getConditionLevel()) ? sanitizeInput(dto.getConditionLevel()) : "良好";
        String originalDesc = StringUtils.hasText(dto.getOriginalDescription()) ? sanitizeInput(dto.getOriginalDescription()) : title;
        String location = StringUtils.hasText(dto.getLocation()) ? sanitizeInput(dto.getLocation()) : "校园宿舍区/图书馆周边";

        String userPrompt = String.format(
                AiPromptConstants.DESCRIPTION_USER_TEMPLATE,
                title, condition, originalDesc, location
        );

        try {
            String rawJson = deepSeekClient.chatCompletion(AiPromptConstants.DESCRIPTION_SYSTEM_PROMPT, userPrompt);
            String cleanJson = extractJson(rawJson);
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            String description = rootNode.has("description") ? rootNode.path("description").asText() : null;
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = rootNode.path("tags");
            if (tagsNode.isArray()) {
                for (JsonNode t : tagsNode) {
                    String tagText = t.asText();
                    if (StringUtils.hasText(tagText)) {
                        String cleanTag = tagText.trim();
                        if (cleanTag.length() <= 20 && !tags.contains(cleanTag)) {
                            tags.add(cleanTag);
                        }
                    }
                    if (tags.size() >= 8) {
                        break;
                    }
                }
            }

            if (validateDescriptionOutput(description, tags)) {
                return AiDescriptionVO.builder()
                        .generatedDescription(description.trim())
                        .tags(tags)
                        .degraded(false)
                        .build();
            } else {
                log.warn("DeepSeek 描述生成未通过 Schema 或业务规则校验，启动本地规则降级");
            }
        } catch (Exception e) {
            log.warn("DeepSeek 描述生成接口调用或解析失败，启动本地优雅降级: {}", e.getMessage());
        }

        // 本地降级兜底生成
        return buildDegradedDescription(dto, condition, originalDesc, location);
    }

    @Override
    public AiCategoryVO recommendCategory(AiCategoryDTO dto) {
        String title = sanitizeInput(dto.getTitle());
        String desc = StringUtils.hasText(dto.getDescription()) ? sanitizeInput(dto.getDescription()) : title;

        String userPrompt = String.format(AiPromptConstants.CATEGORY_USER_TEMPLATE, title, desc);

        try {
            String rawJson = deepSeekClient.chatCompletion(AiPromptConstants.CATEGORY_SYSTEM_PROMPT, userPrompt);
            String cleanJson = extractJson(rawJson);
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            JsonNode idNode = rootNode.path("categoryId");
            Long categoryId = (idNode != null && idNode.isNumber()) ? idNode.asLong() : null;
            String categoryName = rootNode.has("categoryName") ? rootNode.path("categoryName").asText() : null;
            Double confidence = (rootNode.has("confidence") && rootNode.path("confidence").isNumber())
                    ? rootNode.path("confidence").asDouble() : null;
            String reason = rootNode.path("reason").asText("AI 推荐分类");

            if (validateCategoryOutput(categoryId, categoryName, confidence)) {
                return AiCategoryVO.builder()
                        .categoryId(categoryId)
                        .categoryName(categoryName.trim())
                        .confidence(confidence != null ? confidence : 0.90)
                        .reason(reason)
                        .degraded(false)
                        .build();
            } else {
                log.warn("DeepSeek 分类推荐未通过真实性或一致性校验，启动本地规则降级");
            }
        } catch (Exception e) {
            log.warn("DeepSeek 分类推荐接口调用或解析失败，启动本地规则降级: {}", e.getMessage());
        }

        // 本地规则降级兜底
        return buildDegradedCategory(title, desc);
    }

    @Override
    public AiPriceVO suggestPrice(AiPriceDTO dto) {
        String title = sanitizeInput(dto.getTitle());
        String condition = StringUtils.hasText(dto.getConditionLevel()) ? sanitizeInput(dto.getConditionLevel()) : "9成新";
        String origPriceStr = (dto.getOriginalPrice() != null) ? dto.getOriginalPrice().toString() + "元" : "未提供";
        String category = StringUtils.hasText(dto.getCategoryName()) ? sanitizeInput(dto.getCategoryName()) : "二手闲置";

        String userPrompt = String.format(AiPromptConstants.PRICE_USER_TEMPLATE, title, condition, origPriceStr, category);

        try {
            String rawJson = deepSeekClient.chatCompletion(AiPromptConstants.PRICE_SYSTEM_PROMPT, userPrompt);
            String cleanJson = extractJson(rawJson);
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            JsonNode minNode = rootNode.path("minPrice");
            JsonNode maxNode = rootNode.path("maxPrice");
            JsonNode sugNode = rootNode.path("suggestedPrice");

            if (minNode != null && minNode.isNumber() && maxNode != null && maxNode.isNumber() && sugNode != null && sugNode.isNumber()) {
                BigDecimal minPrice = BigDecimal.valueOf(minNode.asDouble()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal maxPrice = BigDecimal.valueOf(maxNode.asDouble()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal suggestedPrice = BigDecimal.valueOf(sugNode.asDouble()).setScale(2, RoundingMode.HALF_UP);
                String reason = rootNode.path("reason").asText("根据校园二手行情评估");

                if (validatePriceOutput(minPrice, suggestedPrice, maxPrice)) {
                    return AiPriceVO.builder()
                            .minPrice(minPrice)
                            .maxPrice(maxPrice)
                            .suggestedPrice(suggestedPrice)
                            .reason(reason)
                            .degraded(false)
                            .build();
                } else {
                    log.warn("DeepSeek 价格建议未通过区间或上下限校验，启动本地算法降级");
                }
            } else {
                log.warn("DeepSeek 价格建议返回非数值或缺少必需价格字段，启动本地算法降级");
            }
        } catch (Exception e) {
            log.warn("DeepSeek 价格建议接口调用或解析失败，启动本地算法降级: {}", e.getMessage());
        }

        // 本地降级兜底估价
        return buildDegradedPrice(dto, condition);
    }

    /**
     * 校验描述生成输出
     */
    private boolean validateDescriptionOutput(String description, List<String> tags) {
        if (!StringUtils.hasText(description)) {
            log.warn("AI 描述生成校验失败：描述文本为空");
            return false;
        }
        String trimmed = description.trim();
        if (trimmed.length() < 10) {
            log.warn("AI 描述生成校验失败：描述过短 (长度: {})", trimmed.length());
            return false;
        }
        if (trimmed.length() > 1500) {
            log.warn("AI 描述生成校验失败：描述超长 (长度: {})", trimmed.length());
            return false;
        }
        if (tags == null || tags.isEmpty()) {
            log.warn("AI 描述生成校验失败：未生成有效标签");
            return false;
        }
        return true;
    }

    /**
     * 校验分类推荐输出：严禁直接盲信大模型生成结果，必须对齐数据库真实分类
     */
    private boolean validateCategoryOutput(Long categoryId, String categoryName, Double confidence) {
        if (categoryId == null || categoryId <= 0) {
            log.warn("AI 推荐分类校验失败：分类 ID 无效 ({})", categoryId);
            return false;
        }
        if (!StringUtils.hasText(categoryName)) {
            log.warn("AI 推荐分类校验失败：分类名称为空");
            return false;
        }
        if (confidence == null || confidence < 0.0 || confidence > 1.0) {
            log.warn("AI 推荐分类校验失败：置信度非法 ({})", confidence);
            return false;
        }

        // 核心安全校验：检查数据库物理存在性与启用状态
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            log.warn("AI 推荐分类校验失败：分类 ID [{}] 在数据库中不存在 (拒绝采信幻觉 ID)", categoryId);
            return false;
        }
        if (category.getStatus() == null || category.getStatus() != 1) {
            log.warn("AI 推荐分类校验失败：分类 ID [{}] 已被禁用 (status={})", categoryId, category.getStatus());
            return false;
        }

        // 核心一致性校验：校验分类名称匹配度
        String dbName = category.getName().trim().toLowerCase();
        String aiName = categoryName.trim().toLowerCase();
        if (!dbName.equals(aiName) && !dbName.contains(aiName) && !aiName.contains(dbName)) {
            log.warn("AI 推荐分类校验失败：分类名称不匹配 (DB: '{}', AI: '{}')", dbName, aiName);
            return false;
        }

        return true;
    }

    /**
     * 校验价格建议输出：满足 0 <= min <= suggested <= max <= 1000000
     */
    private boolean validatePriceOutput(BigDecimal minPrice, BigDecimal suggestedPrice, BigDecimal maxPrice) {
        if (minPrice == null || maxPrice == null || suggestedPrice == null) {
            log.warn("AI 价格建议校验失败：价格字段存在 null");
            return false;
        }
        if (minPrice.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("AI 价格建议校验失败：最低价为负数 ({})", minPrice);
            return false;
        }
        if (suggestedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("AI 价格建议校验失败：建议价小于等于0 ({})", suggestedPrice);
            return false;
        }
        if (maxPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("AI 价格建议校验失败：最高价小于等于0 ({})", maxPrice);
            return false;
        }
        // 区间逻辑：min <= suggested <= max
        if (minPrice.compareTo(suggestedPrice) > 0) {
            log.warn("AI 价格建议校验失败：最低价 ({}) 大于建议价 ({})", minPrice, suggestedPrice);
            return false;
        }
        if (suggestedPrice.compareTo(maxPrice) > 0) {
            log.warn("AI 价格建议校验失败：建议价 ({}) 大于最高价 ({})", suggestedPrice, maxPrice);
            return false;
        }
        // 极端离谱价格防范
        if (maxPrice.compareTo(MAX_PRICE_CEILING) > 0) {
            log.warn("AI 价格建议校验失败：最高价 ({}) 超过合理上限 ({})", maxPrice, MAX_PRICE_CEILING);
            return false;
        }

        return true;
    }

    /**
     * 输入清洗与防标签注入处理
     */
    private String sanitizeInput(String input) {
        if (input == null) {
            return "";
        }
        // 过滤不可见控制字符 (保留换行和制表符)
        String cleaned = input.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        // 防闭合注入：中和用户输入中的自定义边界标签
        cleaned = cleaned.replace("</USER_DATA>", "[USER_DATA_CLOSED]")
                         .replace("<USER_DATA>", "[USER_DATA_OPEN]");
        return cleaned.trim();
    }

    /**
     * 辅助方法：去除 Markdown 代码块包裹 (```json ... ```)
     */
    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{}";
        }
        String content = raw.trim();
        if (content.startsWith("```json")) {
            content = content.substring(7);
        } else if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        return content.trim();
    }

    /**
     * 本地结构化描述降级
     */
    private AiDescriptionVO buildDegradedDescription(AiDescriptionDTO dto, String condition, String originalDesc, String location) {
        StringBuilder sb = new StringBuilder();
        sb.append("【成色外观】成色状态良好，评级为").append(condition).append("，外观整洁，无严重磕碰或损伤。\n");
        sb.append("【规格详情】").append(originalDesc).append("。各项核心功能完好，配件齐全，到手即用。\n");
        sb.append("【转手原因】毕业/换新闲置整理，诚意低价转手给需要的本校同学，杜绝浪费。\n");
        sb.append("【自提验货】支持校内当面验货交付（推荐地点：").append(location).append("），当场验收满意后再付款，省心快捷！");

        List<String> tags = new ArrayList<>();
        tags.add(condition);
        tags.add("校园面交");
        tags.add("功能正常");
        tags.add("支持当面验货");

        return AiDescriptionVO.builder()
                .generatedDescription(sb.toString())
                .tags(tags)
                .degraded(true)
                .build();
    }

    /**
     * 本地智能分类规则降级
     */
    private AiCategoryVO buildDegradedCategory(String title, String desc) {
        String text = (title + " " + desc).toLowerCase();

        if (text.matches(".*(手机|iphone|华为|小米|荣耀|vivo|oppo|红米|一加).*")) {
            return AiCategoryVO.builder().categoryId(101L).categoryName("手机").confidence(0.85).reason("关键词匹配推荐 (手机品类)").degraded(true).build();
        }
        if (text.matches(".*(电脑|笔记本|macbook|thinkpad|联想|华硕|戴尔|主机|显示器).*")) {
            return AiCategoryVO.builder().categoryId(102L).categoryName("电脑").confidence(0.85).reason("关键词匹配推荐 (电脑品类)").degraded(true).build();
        }
        if (text.matches(".*(平板|ipad|matepad|安卓平板).*")) {
            return AiCategoryVO.builder().categoryId(103L).categoryName("平板").confidence(0.85).reason("关键词匹配推荐 (平板品类)").degraded(true).build();
        }
        if (text.matches(".*(考研|英语|六级|四级|肖秀荣|红宝书|真题|笔记).*")) {
            return AiCategoryVO.builder().categoryId(201L).categoryName("考研资料").confidence(0.85).reason("关键词匹配推荐 (考研复习资料)").degraded(true).build();
        }
        if (text.matches(".*(教材|课本|高数|线代|概率论|大学物理|微积分|讲义).*")) {
            return AiCategoryVO.builder().categoryId(202L).categoryName("专业教材").confidence(0.85).reason("关键词匹配推荐 (高校教材)").degraded(true).build();
        }
        if (text.matches(".*(自行车|单车|山地车|公路车|电动车|死飞).*")) {
            return AiCategoryVO.builder().categoryId(501L).categoryName("自行车").confidence(0.85).reason("关键词匹配推荐 (骑行出行)").degraded(true).build();
        }
        if (text.matches(".*(宿舍|台灯|插座|晾衣|收纳|垫子|床上|风扇|水杯).*")) {
            return AiCategoryVO.builder().categoryId(301L).categoryName("宿舍用品").confidence(0.80).reason("关键词匹配推荐 (宿舍生活用品)").degraded(true).build();
        }
        if (text.matches(".*(衣|裙|裤|鞋|外套|卫衣|羽绒服|包|背包).*")) {
            return AiCategoryVO.builder().categoryId(4L).categoryName("服饰鞋包").confidence(0.80).reason("关键词匹配推荐 (服饰品类)").degraded(true).build();
        }

        // 默认电子产品大类
        return AiCategoryVO.builder().categoryId(1L).categoryName("电子产品").confidence(0.60).reason("综合默认品类推荐").degraded(true).build();
    }

    /**
     * 本地价格折旧算法降级
     */
    private AiPriceVO buildDegradedPrice(AiPriceDTO dto, String condition) {
        BigDecimal original = dto.getOriginalPrice();
        if (original != null && original.compareTo(BigDecimal.ZERO) > 0) {
            double minRatio;
            double maxRatio;
            double sugRatio;

            switch (condition) {
                case "全新":
                    minRatio = 0.80; maxRatio = 0.90; sugRatio = 0.85; break;
                case "99新":
                    minRatio = 0.70; maxRatio = 0.80; sugRatio = 0.75; break;
                case "95新":
                    minRatio = 0.55; maxRatio = 0.70; sugRatio = 0.62; break;
                case "9成新":
                    minRatio = 0.45; maxRatio = 0.60; sugRatio = 0.50; break;
                case "8成新以下":
                    minRatio = 0.25; maxRatio = 0.40; sugRatio = 0.30; break;
                default:
                    minRatio = 0.50; maxRatio = 0.65; sugRatio = 0.55; break;
            }

            BigDecimal minPrice = original.multiply(BigDecimal.valueOf(minRatio)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal maxPrice = original.multiply(BigDecimal.valueOf(maxRatio)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sugPrice = original.multiply(BigDecimal.valueOf(sugRatio)).setScale(2, RoundingMode.HALF_UP);

            String reason = String.format("根据官方原价 ¥%s 及成色 [%s] 的校园二手折旧模型评估，建议在原价 %.0f%%~%.0f%% 区间挂牌，推荐价 ¥%s。",
                    original, condition, minRatio * 100, maxRatio * 100, sugPrice);

            return AiPriceVO.builder()
                    .minPrice(minPrice)
                    .maxPrice(maxPrice)
                    .suggestedPrice(sugPrice)
                    .reason(reason)
                    .degraded(true)
                    .build();
        }

        // 无原价默认兜底
        return AiPriceVO.builder()
                .minPrice(new BigDecimal("30.00"))
                .maxPrice(new BigDecimal("100.00"))
                .suggestedPrice(new BigDecimal("60.00"))
                .reason("未提供原购入价，建议参考校内同类商品普遍挂牌价（约 30~100 元），可根据具体新旧程度微调。")
                .degraded(true)
                .build();
    }
}
