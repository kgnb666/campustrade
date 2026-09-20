package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.limit.RedisRateLimiter;
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

/**
 * 举报工单领域业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final int DAILY_REPORT_LIMIT = 10;

    /** 每日举报计数键的 TTL：24 小时（每次计数都会刷新，保证键不会永久存在）。 */
    private static final long DAILY_LIMIT_TTL_SECONDS = 24 * 3600L;

    private final ReportMapper reportMapper;
    private final GoodsMapper goodsMapper;
    private final ReviewMapper reviewMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisRateLimiter redisRateLimiter;

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

        // 4. 每日频控校验：先原子占用配额再判定（每日上限 10 次）
        //    并发下"先 GET 再判断再 INCR"会超发（两个请求同时读到 9 都放行）；
        //    Redis 不可用时不再直接放行（fail-open 等于限流形同虚设），
        //    而是明确降级为"按 userId 查数据库当日举报数"兜底。
        enforceDailyLimit(reporterId);

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

        // 7. 计数已在第 4 步随配额占用完成（INCR 优先），此处只保留成功日志
        log.info("用户举报配额已占用: reporterId={}, dailyLimit={}", reporterId, DAILY_REPORT_LIMIT);

        return convertToVO(report);
    }

    /**
     * 每日举报频控（每日上限 {@value #DAILY_REPORT_LIMIT} 次）。
     *
     * <h2>为什么是"先 INCR 再判定"</h2>
     * <p>原实现是 GET → 判断 → INSERT → INCR：并发下多个请求会同时读到"尚未超限"，
     * 于是全部插入，实际举报数超过上限（超发）。把计数放到最前面，每个请求先原子占用一个配额，
     * 只有 {@code count <= limit} 的请求继续执行；超出者在任何业务动作之前就被拒。</p>
     *
     * <h2>TTL 与降级</h2>
     * <ul>
     *   <li>每次计数都刷新 TTL（24 小时），因此不会出现"没有过期时间的计数键"把用户永久卡死；</li>
     *   <li>Redis 不可用时<b>不再 fail-open</b>：改按数据库中的"当日举报数"兜底校验
     *       （{@code created_time >= 今日 0 点}）。降级路径同样有上限保证，只是精度略低
     *       （计数窗口按自然日切分，且并发下可能少算极少量正在提交中的举报）。</li>
     * </ul>
     */
    private void enforceDailyLimit(Long reporterId) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String redisKey = RedisKeyConstants.reportDailyLimitKey(reporterId, today);
        try {
            long count = redisRateLimiter.increment(redisKey, DAILY_LIMIT_TTL_SECONDS);
            if (count > DAILY_REPORT_LIMIT) {
                log.warn("用户举报触发每日限频: userId={}, today={}, count={}, limit={}",
                        reporterId, today, count, DAILY_REPORT_LIMIT);
                throw new BusinessException(429, "您今日举报次数已达上限，请明天再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            long dbCount = countTodayReportsInDb(reporterId);
            log.warn("Redis 举报限频计数不可用，降级为数据库当日计数兜底: userId={}, dbCount={}, limit={}, msg={}",
                    reporterId, dbCount, DAILY_REPORT_LIMIT, e.getMessage());
            if (dbCount >= DAILY_REPORT_LIMIT) {
                throw new BusinessException(429, "您今日举报次数已达上限，请明天再试");
            }
        }
    }

    /** Redis 不可用时的降级计数：按 userId 统计数据库里今日已提交的举报数。 */
    private long countTodayReportsInDb(Long reporterId) {
        try {
            Long count = reportMapper.selectCount(
                    new LambdaQueryWrapper<Report>()
                            .eq(Report::getReporterId, reporterId)
                            .ge(Report::getCreatedTime, LocalDate.now().atStartOfDay())
            );
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 兜底路径自身也失败：此时无法证明调用方仍在配额内，按"拒绝"处理（fail-closed）
            log.error("举报限流的数据库兜底计数同样失败，按超限处理: userId={}", reporterId, e);
            return DAILY_REPORT_LIMIT;
        }
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
        String reasonDesc;
        try {
            reasonDesc = ReportReasonType.valueOf(report.getReasonType()).getDescription();
        } catch (Exception e) {
            // 枚举值域之外的存量数据（历史造数/人工改库）：回退为原始字面量，但必须留痕
            log.debug("举报原因类型不在枚举值域内，回退展示原始值: reasonType={}, msg={}",
                    report.getReasonType(), e.getMessage());
            reasonDesc = report.getReasonType();
        }

        String statusDesc;
        try {
            statusDesc = ReportStatus.valueOf(report.getStatus()).getDescription();
        } catch (Exception e) {
            log.debug("举报状态不在枚举值域内，回退展示原始值: status={}, msg={}",
                    report.getStatus(), e.getMessage());
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
