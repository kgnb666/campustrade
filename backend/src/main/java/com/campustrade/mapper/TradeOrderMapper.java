package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.TradeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 交易订单持久层 Mapper 接口
 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    /**
     * 悲观排他行锁查询订单 (FOR UPDATE)
     * 确保状态机并发跃迁时串行化执行，杜绝并发竞态覆写
     */
    @Select("SELECT * FROM campus_trade.trade_order WHERE id = #{id} FOR UPDATE")
    TradeOrder selectByIdForUpdate(@Param("id") Long id);
}

