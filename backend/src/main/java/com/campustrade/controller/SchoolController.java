package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.service.SchoolService;
import com.campustrade.vo.SchoolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 高校学校列表控制器
 */
@RestController
@RequestMapping("/school")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolService schoolService;

    /**
     * 查询支持认证的高校列表
     */
    @GetMapping("/list")
    public Result<List<SchoolVO>> listSchools() {
        return schoolService.listActiveSchools();
    }
}
