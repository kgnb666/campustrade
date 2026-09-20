package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户表 Mapper 接口
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子冻结违规用户（定点更新，仅改 status / updated_time）。
     *
     * @return 受影响行数：1 = 已冻结；0 = 用户已是 FROZEN（或不存在）
     */
    @Update("UPDATE campus_trade.\"user\" SET status = 'FROZEN', updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{userId} AND status <> 'FROZEN'")
    int freezeForGovernance(@Param("userId") Long userId);
}
