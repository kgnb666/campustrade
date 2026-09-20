package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.dto.report.CreateReportRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.Review;
import com.campustrade.entity.User;
import com.campustrade.enums.ReportReasonType;
import com.campustrade.enums.ReportStatus;
import com.campustrade.enums.ReportTargetType;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.ReviewMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.ReportService;
import com.campustrade.vo.report.ReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 举报工单领域业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final int DAILY_REPORT_LIMIT = 10;
    private static final String DAILY_LIMIT_PREFIX = "report:daily:limit:";

    private final ReportMapper reportMapper;
    private final GoodsMapper goodsMapper;
    private final ReviewMapper reviewMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportVO submitReport(Long reporterId, CreateReportRequest request) {
        if (reporterId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (request == null) {
            throw new BusinessException(400, "举报入参不能为空");
        }

        // 1. 规范并校验目标类型
        String targetTypeStr = request.getTargetType() != null ? request.getTargetType().trim().toUpperCase() : "";
        ReportTargetType targetTypeEnum;
        try {
            targetTypeEnum = ReportTargetType.valueOf(targetTypeStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的举报目标类型: " + request.getTargetType());
        }

        // 2. 规范并校验原因类型
        String reasonTypeStr = request.getReasonType() != null ? request.getReasonType().trim().toUpperCase() : "";
        ReportReasonType reasonTypeEnum;
        try {
            reasonTypeEnum = ReportReasonType.valueOf(reasonTypeStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的举报原因类型: " + request.getReasonType());
        }

        Long targetId = request.getTargetId();
        if (targetId == null) {
            throw new BusinessException(400, "目标ID不能为空");
        }

        // 3. 目标多态存在性校验与严禁自举报防范
        validateTargetAndSelfReport(reporterId, targetTypeEnum, targetId);

        // 4. Redis 每日频控校验 (每日上限 10 次，异常降级 fail-open)
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String redisKey = DAILY_LIMIT_PREFIX + reporterId + ":" + today;
        try {
            String countStr = stringRedisTemplate.opsForValue().get(redisKey);
            if (countStr != null && Integer.parseInt(countStr) >= DAILY_REPORT_LIMIT) {
                log.warn("用户举报触发每日限频: userId={}, today={}", reporterId, today);
                throw new BusinessException(429, "您今日举报次数已达上限，请明天再试");
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("Redis 每日限频读取异常，降级放行: userId={}, msg={}", reporterId, e.getMessage());
        }

        // 5. 待处理工单防重复检查 (与 uk_report_active 配合)
        Report pending = reportMapper.selectOne(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getReporterId, reporterId)
                        .eq(Report::getTargetType, targetTypeEnum.getCode())
                        .eq(Report::getTargetId, targetId)
                        .eq(Report::getStatus, ReportStatus.PENDING.getCode())
        );
        if (pending != null) {
            throw new BusinessException(409, "该目标您已提交举报，管理员正在核查中");
        }

        // 6. 持久化举报工单
        LocalDateTime now = LocalDateTime.now();
        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType(targetTypeEnum.getCode())
                .targetId(targetId)
                .reasonType(reasonTypeEnum.getCode())
                .description(StringUtils.hasText(request.getDescription()) ? request.getDescription().trim() : null)
                .evidenceImages(StringUtils.hasText(request.getEvidenceImages()) ? request.getEvidenceImages().trim() : null)
                .status(ReportStatus.PENDING.getCode())
                .createdTime(now)
                .updatedTime(now)
                .build();

        try {
            reportMapper.insert(report);
            log.info("用户提交举报工单成功: reportId={}, reporterId={}, targetType={}, targetId={}",
                    report.getId(), reporterId, targetTypeEnum.getCode(), targetId);
        } catch (DuplicateKeyException e) {
            log.warn("并发提交举报命中 uk_report_active 唯一索引: reporterId={}, targetType={}, targetId={}",
                    reporterId, targetTypeEnum.getCode(), targetId);
            throw new BusinessException(409, "该目标您已提交举报，管理员正在核查中");
        }

        // 7. 增加 Redis 每日计数
        try {
            Long current = stringRedisTemplate.opsForValue().increment(redisKey, 1);
            if (current != null && current == 1) {
                stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.warn("Redis 每日限频计数异常: userId={}, msg={}", reporterId, e.getMessage());
        }

        return convertToVO(report);
    }

    @Override
    public IPage<ReportVO> listMyReports(Long reporterId, int page, int size) {
        if (reporterId == null) {
            throw new BusinessException(401, "请先登录");
        }
        int curPage = page > 0 ? page : 1;
        int pageSize = (size > 0 && size <= 100) ? size : 10;

        Page<Report> reportPage = new Page<>(curPage, pageSize);
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .eq(Report::getReporterId, reporterId)
                .orderByDesc(Report::getCreatedTime);

        IPage<Report> paged = reportMapper.selectPage(reportPage, wrapper);
        return paged.convert(this::convertToVO);
    }

    /**
     * 校验目标存在性与严禁自我举报
     */
    private void validateTargetAndSelfReport(Long reporterId, ReportTargetType targetType, Long targetId) {
        switch (targetType) {
            case GOODS:
                Goods goods = goodsMapper.selectById(targetId);
                if (goods == null) {
                    throw new BusinessException(404, "被举报的商品不存在");
                }
                if (goods.getSellerId().equals(reporterId)) {
                    throw new BusinessException(400, "不能举报自己发布的商品");
                }
                break;
            case REVIEW:
                Review review = reviewMapper.selectById(targetId);
                if (review == null) {
                    throw new BusinessException(404, "被举报的评价不存在");
                }
                if (review.getReviewerId().equals(reporterId)) {
                    throw new BusinessException(400, "不能举报自己发表的评价");
                }
                break;
            case USER:
                User user = userMapper.selectById(targetId);
                if (user == null) {
                    throw new BusinessException(404, "被举报的用户不存在");
                }
                if (targetId.equals(reporterId)) {
                    throw new BusinessException(400, "不能举报自己");
                }
                break;
            default:
                throw new BusinessException(400, "不支持的举报目标类型");
        }
    }

    private ReportVO convertToVO(Report report) {
        if (report == null) {
            return null;
        }
        String reasonDesc = null;
        try {
            reasonDesc = ReportReasonType.valueOf(report.getReasonType()).getDescription();
        } catch (Exception ignored) {
            reasonDesc = report.getReasonType();
        }

        String statusDesc = null;
        try {
            statusDesc = ReportStatus.valueOf(report.getStatus()).getDescription();
        } catch (Exception ignored) {
            statusDesc = report.getStatus();
        }

        return ReportVO.builder()
                .id(report.getId())
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .reasonType(report.getReasonType())
                .reasonDesc(reasonDesc)
                .description(report.getDescription())
                .evidenceImages(report.getEvidenceImages())
                .status(report.getStatus())
                .statusDesc(statusDesc)
                .handleResult(report.getHandleResult())
                .handledTime(report.getHandledTime())
                .createdTime(report.getCreatedTime())
                .build();
    }
}
