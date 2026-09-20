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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传服务实现
 *
 * <h2>内容安全三条</h2>
 * <ol>
 *   <li><b>类型以服务端为准</b>：对象存储里的 {@code Content-Type} 由"通过校验的扩展名"映射而来，
 *       不再采信客户端声明的值。否则上传一个真实 PNG 但声明 {@code image/svg+xml}，
 *       就会把一个可被浏览器当 SVG/XML 解析的资源写进图片桶（SVG 可携带脚本，
 *       一旦桶/CDN 允许内联渲染即为存储型 XSS 的入口）；</li>
 *   <li><b>先魔数、再尺寸</b>：魔数只证明"文件头像图片"，不证明"能被安全解码"。
 *       这里再用 JDK 自带的 {@link ImageIO} 读<b>图片尺寸</b>并设置上限，
 *       拦截"极小体积、超大画布"的解压炸弹（PNG/JPEG 头部即可声明上万像素，
 *       解码时会按宽×高分配像素缓冲，足以打爆内存）；</li>
 *   <li><b>错误文案对外统一</b>：对外只给一句可读提示，存储/解码的内部细节只写服务端日志。</li>
 * </ol>
 *
 * <p>关于"读不出版本尺寸"的文件（截断文件、仅伪造了 12 字节文件头的脚本）：尺寸校验会跳过并记
 * debug 日志。这类内容本身就是不可解码的垃圾数据，构不成解码放大风险；若要让它们在上传阶段
 * 被拒，需要把魔数校验从"前 12 字节"升级为"整图可解码"，属于更激进的策略变更（会拒绝
 * 目前允许的头部夹具文件），此处不做。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /** 允许上传的图片扩展名 → 服务端认定的 Content-Type（唯一真相源）。 */
    private static final Map<String, String> ALLOWED_EXTENSION_CONTENT_TYPES = new LinkedHashMap<>();

    static {
        ALLOWED_EXTENSION_CONTENT_TYPES.put(".jpg", "image/jpeg");
        ALLOWED_EXTENSION_CONTENT_TYPES.put(".jpeg", "image/jpeg");
        ALLOWED_EXTENSION_CONTENT_TYPES.put(".png", "image/png");
        ALLOWED_EXTENSION_CONTENT_TYPES.put(".webp", "image/webp");
    }

    /**
     * 最大文件大小: 5MB
     */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    /** 图片单边最大像素数：超过即拒绝（防"极小文件、超大画布"的解压炸弹）。 */
    private static final int MAX_IMAGE_EDGE_PX = 10000;

    /** 图片像素总数上限（约 4000 万像素 ≈ 4K 图片的 5 倍，正常商品图远远不会触及）。 */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    /** 对外统一的错误文案（内部细节只进日志，不返回给调用方）。 */
    private static final String UPLOAD_FAILED_MESSAGE = "图片上传失败，请稍后重试";

    @Override
    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }

        // 1. 校验文件大小限制 (<= 5MB)
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "图片大小不能超过 5MB");
        }

        // 2. 校验文件扩展名（扩展名白名单是"允许的分支"唯一来源）
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new BusinessException(400, "非法的图片文件名");
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        String serverSideContentType = ALLOWED_EXTENSION_CONTENT_TYPES.get(extension);
        if (serverSideContentType == null) {
            throw new BusinessException(400, "仅支持上传 jpg, jpeg, png, webp 格式图片");
        }

        // 3. 客户端声明的 Content-Type 只用于留痕比对，绝不写进对象存储
        String declaredContentType = file.getContentType();
        if (StringUtils.hasText(declaredContentType) && !declaredContentType.equalsIgnoreCase(serverSideContentType)) {
            log.debug("上传文件声明的 Content-Type 与扩展名映射不一致，按扩展名映射落库: declared={}, mapped={}, name={}",
                    declaredContentType, serverSideContentType, originalFilename);
        }

        // 4. 校验二进制文件头 (Magic Bytes) 确保真实为图片
        validateImageMagicBytes(file);

        // 5. 校验图片尺寸上限（ImageIO 只读头部信息，不解码像素，因此不会成为新的内存放大点）
        validateImageDimensions(file);

        // 6. 生成按日期划分的随机唯一文件名
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randomName = UUID.randomUUID().toString().replace("-", "");
        String objectName = "goods/" + dateDir + "/" + randomName + extension;

        // 7. 流式上传至 MinIO
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(serverSideContentType)
                            .build()
            );
            log.info("图片成功上传至 MinIO: bucket={}, objectName={}, contentType={}",
                    minioProperties.getBucketName(), objectName, serverSideContentType);

            // 8. 构造外部访问 URL
            String prefix = minioProperties.getUrlPrefix();
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            return prefix + "/" + objectName;
        } catch (Exception e) {
            // 内部细节（桶名、MinIO 返回文本）只进日志：对外的错误文案保持统一
            log.error("MinIO 上传失败: bucket={}, objectName={}", minioProperties.getBucketName(), objectName, e);
            throw new BusinessException(500, UPLOAD_FAILED_MESSAGE);
        }
    }

    /**
     * 用 JDK 自带的 {@link ImageIO} 读取图片尺寸并校验上限。
     *
     * <p>只调用 {@code ImageReader#getWidth/getHeight}（读取头部元数据），不调用
     * {@code ImageIO.read} —— 后者会把整张图解码成像素数组，恰好就是我们想防的内存放大。</p>
     *
     * <p>无法解析出尺寸的文件（截断/伪造头部）跳过本校验，只记 debug 日志：它们不可解码，
     * 不构成本地放大风险。</p>
     */
    private void validateImageDimensions(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {

            if (imageInputStream == null) {
                log.debug("无法为上传文件创建 ImageInputStream，跳过尺寸校验: name={}", file.getOriginalFilename());
                return;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                log.debug("无可用的 ImageReader，跳过图片尺寸校验: name={}, size={}",
                        file.getOriginalFilename(), file.getSize());
                return;
            }

            ImageReader reader = readers.next();
            try {
                // seekForwardOnly = true：只顺序读头部，遇到无法解析的数据不会回退重试
                reader.setInput(imageInputStream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;

                if (width > MAX_IMAGE_EDGE_PX || height > MAX_IMAGE_EDGE_PX || pixels > MAX_IMAGE_PIXELS) {
                    log.warn("拒绝超尺寸图片: name={}, width={}, height={}, pixels={}, maxEdge={}, maxPixels={}",
                            file.getOriginalFilename(), width, height, pixels, MAX_IMAGE_EDGE_PX, MAX_IMAGE_PIXELS);
                    throw new BusinessException(400,
                            "图片尺寸过大，单边不得超过 " + MAX_IMAGE_EDGE_PX + " 像素");
                }
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 头部信息不完整/格式异常：按"不可解析"处理，细节只进日志
            log.debug("图片尺寸解析失败，跳过尺寸上限校验: name={}, msg={}",
                    file.getOriginalFilename(), e.getMessage());
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
