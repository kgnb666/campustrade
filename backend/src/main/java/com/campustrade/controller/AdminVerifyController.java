package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.common.annotation.CurrentUser;
import com.campustrade.common.util.ClientIpUtils;
import com.campustrade.dto.AdminVerifyReviewRequest;
import com.campustrade.entity.User;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.service.AdminVerifyService;
import com.campustrade.vo.AdminVerifyReviewVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员审核「无邮箱通道」校园认证材料。
 *
 * <p>与举报治理（{@code /admin/reports}）并列的另一条管理端能力：
 * 那条处理"违规内容"，这条处理"身份材料"。两者都严格限制 ROLE_ADMIN。</p>
 */
@Slf4j
@RestController
@RequestMapping({"/admin", "/api/admin"})
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminVerifyController {

    private final AdminVerifyService adminVerifyService;

    /** 来源 IP 解析统一走 {@link ClientIpUtils}（只有可信代理才采信转发头），审计流水因此不可被伪造 */
    private final ClientIpUtils clientIpUtils;

    /**
     * 认证审核队列（默认只看待审核，按提交时间正序）
     * GET /admin/verifies?status=PENDING&page=1&size=10
     */
    @GetMapping("/verifies")
    public Result<IPage<AdminVerifyReviewVO>> pageVerifies(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") Long page,
            @RequestParam(value = "size", defaultValue = "10") Long size
    ) {
        return Result.success(adminVerifyService.pageVerifies(status, page, size));
    }

    /**
     * 处置一条认证申请（通过 / 驳回）
     * PUT /admin/verifies/{id}/review
     */
    @PutMapping("/verifies/{id}/review")
    public Result<AdminVerifyReviewVO> reviewVerify(
            @CurrentUser User admin,
            @PathVariable("id") Long id,
            @Valid @RequestBody AdminVerifyReviewRequest request,
            HttpServletRequest httpRequest
    ) {
        AdminVerifyReviewVO result = adminVerifyService.reviewVerify(
                admin.getId(),
                admin.getUsername(),
                id,
                request.getAction(),
                request.getNote(),
                clientIpUtils.resolve(httpRequest)
        );
        // 文案按"处置后的真实状态"给：不要根据请求参数猜，避免出现"驳回却提示已通过"这类自相矛盾
        boolean approved = result != null
                && StudentVerifyStatus.SUCCESS.matches(result.getVerifyStatus());
        return Result.success(approved ? AdminVerifyService.APPROVE_MESSAGE : AdminVerifyService.REJECT_MESSAGE, result);
    }
}
