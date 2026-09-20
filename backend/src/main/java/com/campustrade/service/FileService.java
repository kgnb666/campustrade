package com.campustrade.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 */
public interface FileService {

    /**
     * 上传商品图片
     *
     * @param file 图片文件
     * @return 访问 URL
     */
    String uploadImage(MultipartFile file);
}
