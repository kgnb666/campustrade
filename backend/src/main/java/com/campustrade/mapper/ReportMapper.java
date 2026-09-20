package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 举报工单数据访问 Mapper 接口
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    /**
     * 原子处理工单：仅当工单仍处于 PENDING 时才允许落定结论。
     *
     * <p>这是"读 → 判断 PENDING → 写"竞态的根治手段：条件更新天然带行级排他锁，
     * 两个管理员并发处理同一工单时，后到者的 UPDATE 会阻塞到先到者提交，
     * 随后因 {@code status <> 'PENDING'} 命中 0 行而失败，从而只落一次结论、只写一条审计。</p>
     *
     * @return 受影响行数：1 = 处理成功；0 = 工单已被其他管理员处理
     */
    @Update("UPDATE campus_trade.report " +
            "SET status = #{status}, handled_by = #{handledBy}, handled_time = #{handledTime}, " +
            "    handle_result = #{handleResult}, updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{reportId} AND status = 'PENDING'")
    int handleReportAtomic(
            @Param("reportId") Long reportId,
            @Param("status") String status,
            @Param("handledBy") Long handledBy,
            @Param("handledTime") LocalDateTime handledTime,
            @Param("handleResult") String handleResult
    );
}
