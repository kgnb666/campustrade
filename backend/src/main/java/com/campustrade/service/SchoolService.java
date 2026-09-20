package com.campustrade.service;

import com.campustrade.common.Result;
import com.campustrade.vo.SchoolVO;

import java.util.List;

/**
 * 高校字典服务接口
 */
public interface SchoolService {

    /**
     * 查询所有可用高校列表
     */
    Result<List<SchoolVO>> listActiveSchools();
}
