package com.campustrade.service.impl;

import com.campustrade.config.MinioProperties;
import com.campustrade.exception.BusinessException;
import com.campustrade.service.FileService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文件上传服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /**
     * 允许上传的图片扩展名
     */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(".jpg", ".jpeg", ".png", ".webp");

    /**
     * 最大文件大小: 5MB
     */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    @Override
    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }

        // 1. 校验文件大小限制 (<= 5MB)
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "图片大小不能超过 5MB");
        }

        // 2. 校验文件扩展名
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new BusinessException(400, "非法的图片文件名");
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "仅支持上传 jpg, jpeg, png, webp 格式图片");
        }

        // 3. 校验 Content-Type
        String contentType = file.getContentType();
        if (contentType != null && !contentType.startsWith("image/")) {
            throw new BusinessException(400, "上传的文件类型不合法，非有效图片文件");
        }

        // 4. 校验二进制文件头 (Magic Bytes) 确保真实为图片
        validateImageMagicBytes(file);

        // 5. 生成按日期划分的随机唯一文件名
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randomName = UUID.randomUUID().toString().replace("-", "");
        String objectName = "goods/" + dateDir + "/" + randomName + extension;

        // 6. 流式上传至 MinIO
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(contentType != null ? contentType : "image/jpeg")
                            .build()
            );
            log.info("图片成功上传至 MinIO: bucket={}, objectName={}", minioProperties.getBucketName(), objectName);

            // 7. 构造外部访问 URL
            String prefix = minioProperties.getUrlPrefix();
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            return prefix + "/" + objectName;
        } catch (Exception e) {
            log.error("MinIO 上传失败", e);
            throw new BusinessException(500, "图片上传失败: " + e.getMessage());
        }
    }

    /**
     * 校验文件流前若干字节（Magic Bytes）确保真实符合图片规范
     */
    private void validateImageMagicBytes(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[12];
            int read = is.read(header, 0, header.length);
            if (read < 3) {
                throw new BusinessException(400, "文件内容不是有效的图片格式");
            }

            // JPEG: FF D8 FF
            if (isJpeg(header, read)) {
                return;
            }
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (isPng(header, read)) {
                return;
            }
            // WebP: RIFF .... WEBP
            if (isWebp(header, read)) {
                return;
            }

            throw new BusinessException(400, "文件内容不是有效的图片格式");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取图片魔数校验失败", e);
            throw new BusinessException(400, "文件内容不是有效的图片格式");
        }
    }

    private boolean isJpeg(byte[] header, int read) {
        if (read < 3) return false;
        return (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] header, int read) {
        if (read < 8) return false;
        return (header[0] & 0xFF) == 0x89
                && (header[1] & 0xFF) == 0x50
                && (header[2] & 0xFF) == 0x4E
                && (header[3] & 0xFF) == 0x47
                && (header[4] & 0xFF) == 0x0D
                && (header[5] & 0xFF) == 0x0A
                && (header[6] & 0xFF) == 0x1A
                && (header[7] & 0xFF) == 0x0A;
    }

    private boolean isWebp(byte[] header, int read) {
        if (read < 12) return false;
        return (header[0] & 0xFF) == 0x52
                && (header[1] & 0xFF) == 0x49
                && (header[2] & 0xFF) == 0x46
                && (header[3] & 0xFF) == 0x46
                && (header[8] & 0xFF) == 0x57
                && (header[9] & 0xFF) == 0x45
                && (header[10] & 0xFF) == 0x42
                && (header[11] & 0xFF) == 0x50;
    }
}
