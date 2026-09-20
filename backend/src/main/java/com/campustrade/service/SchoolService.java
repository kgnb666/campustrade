package com.campustrade.service;

import com.campustrade.vo.SchoolVO;

import java.util.List;

/**
 * 高校字典服务接口
 *
 * <p>返回领域类型列表，不返回 Web 信封；{@code Result} 包装由 Controller 负责。</p>
 */
public interface SchoolService {

    /**
     * 查询所有可用高校列表
     */
    List<SchoolVO> listActiveSchools();
}
