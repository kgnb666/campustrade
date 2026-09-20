package com.campustrade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CampusTrade 校园二手交易平台后端主启动类
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.campustrade.mapper")
public class CampusTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusTradeApplication.class, args);
    }
}
