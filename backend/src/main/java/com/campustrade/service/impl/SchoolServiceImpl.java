package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Result;
import com.campustrade.entity.CampusSchool;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.service.SchoolService;
import com.campustrade.vo.SchoolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 高校字典服务实现
 */
@Service
@RequiredArgsConstructor
public class SchoolServiceImpl implements SchoolService {

    private final CampusSchoolMapper campusSchoolMapper;

    @Override
    public Result<List<SchoolVO>> listActiveSchools() {
        List<CampusSchool> schools = campusSchoolMapper.selectList(
                new LambdaQueryWrapper<CampusSchool>()
                        .eq(CampusSchool::getStatus, "ACTIVE")
                        .orderByAsc(CampusSchool::getId)
        );

        List<SchoolVO> schoolVOList = schools.stream()
                .map(s -> SchoolVO.builder()
                        .id(s.getId())
                        .schoolName(s.getSchoolName())
                        .schoolCode(s.getSchoolCode())
                        .emailSuffix(s.getEmailSuffix())
                        .build())
                .collect(Collectors.toList());

        return Result.success(schoolVOList);
    }
}
