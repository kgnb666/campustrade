package com.campustrade.task;

import com.campustrade.service.GoodsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商品浏览量定时刷盘调度任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountSyncTask {

    private final GoodsService goodsService;

    /**
     * 每 5 分钟执行一次（应用启动 1 分钟后初次执行）
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void syncGoodsViewCounts() {
        try {
            log.info("开始执行商品浏览量定时同步任务...");
            goodsService.syncViewCounts();
            log.info("商品浏览量定时同步任务执行完成");
        } catch (Exception e) {
            log.error("商品浏览量定时同步任务执行异常: {}", e.getMessage(), e);
        }
    }
}
