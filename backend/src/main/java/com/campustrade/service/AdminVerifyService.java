package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.vo.AdminVerifyReviewVO;

/**
 * 管理员审核「无邮箱通道」校园认证申请。
 *
 * <h2>为什么单独一个服务</h2>
 * <p>认证审核与举报治理虽然都是"管理员处置"，但两者的输入、状态机与合规要求完全不同：
 * 举报是 PENDING → HANDLED_VALID/HANDLED_INVALID 并**副作用很大**（下架商品、屏蔽评价、冻结用户），
 * 认证审核是 PENDING → SUCCESS/REJECTED、副作用只有"点亮/不点亮认证标识"。
 * 混进同一个服务会让两类规则互相牵制（改一处要重新推理另一处），因此按业务边界拆开，
 * 但**审计流水复用同一张 admin_audit_log 表**，保证管理员操作可以在一处回溯。</p>
 */
public interface AdminVerifyService {

    /** 审核通过后的固定提示文案 */
    String APPROVE_MESSAGE = "已通过该学生的校园认证";

    /** 审核驳回后的固定提示文案 */
    String REJECT_MESSAGE = "已驳回该认证申请，请通知学生修改材料后重新提交";

    /**
     * 审核队列（按提交时间正序：先提交先审核）。
     *
     * @param status PENDING / SUCCESS / REJECTED，null 或非法取值按 PENDING 处理
     */
    IPage<AdminVerifyReviewVO> pageVerifies(String status, long page, long size);

    /**
     * 处置一条认证申请。
     *
     * @param action APPROVE（通过）或 REJECT（驳回），大小写不敏感
     * @param note   审核意见；**驳回时必填**（学生需要知道改什么），通过时可选
     */
    AdminVerifyReviewVO reviewVerify(Long adminId, String adminUsername, Long verifyId,
                                     String action, String note, String ipAddress);
}
