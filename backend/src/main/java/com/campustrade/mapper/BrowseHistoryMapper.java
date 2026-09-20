package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.BrowseHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 浏览历史数据持久层接口
 */
@Mapper
public interface BrowseHistoryMapper extends BaseMapper<BrowseHistory> {

    /**
     * 记录浏览足迹（upsert）：并发首次浏览同一商品时，唯一约束 uk_browse_history_user_goods
     * 会让"先查后插"的写法抛唯一键异常并导致 500；改为单语句 upsert 后既不抛异常，
     * 也能把重复浏览刷新为最新时间。
     *
     * @return 受影响行数：1 = 新增或刷新成功
     */
    @Insert("INSERT INTO campus_trade.browse_history (user_id, goods_id, browse_time) " +
            "VALUES (#{userId}, #{goodsId}, #{browseTime}) " +
            "ON CONFLICT (user_id, goods_id) DO UPDATE SET browse_time = EXCLUDED.browse_time")
    int upsertBrowseHistory(
            @Param("userId") Long userId,
            @Param("goodsId") Long goodsId,
            @Param("browseTime") LocalDateTime browseTime
    );
}
