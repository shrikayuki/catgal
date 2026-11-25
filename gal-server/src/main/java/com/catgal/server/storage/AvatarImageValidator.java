/*
 * 阅读说明
 * 检查图片字节大小、扩展名、媒体类型、实际解码与尺寸，返回规范化元数据。
 */
package com.catgal.server.storage;

import com.catgal.common.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AvatarImageValidator {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16_000_000;
    private static final int MAX_FRAMES = 100;
    private static final long MAX_TOTAL_PIXELS = 32_000_000;
    private final Semaphore decoders = new Semaphore(2);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "jpg", "jpeg", "jpeg", "jpeg", "png", "png", "gif", "gif", "webp", "webp");

    public record Image(byte[] content, String extension, String contentType) {}

    public Image validate(MultipartFile file) {
        if (!decoders.tryAcquire()) throw new BusinessException(429, 429, "图片处理繁忙，请稍后重试");
        try {
            return validateImage(file);
        } finally {
            decoders.release();
        }
    }

    // 解析实际图片内容与尺寸，结合声明类型和扩展名共同验证上传元数据。
    private Image validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw BusinessException.badRequest("文件不能为空");
        if (file.getSize() > MAX_BYTES) throw BusinessException.badRequest("图片大小不能超过2MB");
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String format = dot < 0 ? null : EXTENSIONS.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (format == null) throw BusinessException.badRequest("只支持 JPG、PNG、GIF、WEBP 格式");
        String contentType = "image/" + format;
        if (file.getContentType() == null || !contentType.equals(file.getContentType().toLowerCase(Locale.ROOT))) {
            throw BusinessException.badRequest("文件 MIME 与扩展名不匹配");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw BusinessException.internalError("读取头像失败", exception);
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw BusinessException.badRequest("头像内容大小无效，最大2MB");
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw BusinessException.badRequest("文件内容无法识别为图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                if (!format.equals(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw BusinessException.badRequest("图片内容与扩展名不匹配");
                }
                int frames = reader.getNumImages(true);
                if (frames < 1 || frames > MAX_FRAMES) throw BusinessException.badRequest("头像最多支持100帧");
                long totalPixels = 0;
                for (int frame = 0; frame < frames; frame++) {
                    int width = reader.getWidth(frame);
                    int height = reader.getHeight(frame);
                    long pixels = (long) width * height;
                    totalPixels += pixels;
                    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                            || pixels > MAX_PIXELS || totalPixels > MAX_TOTAL_PIXELS) {
                        throw BusinessException.badRequest("头像尺寸或总像素超过限制");
                    }
                    BufferedImage decoded = reader.read(frame);
                    if (decoded == null) throw BusinessException.badRequest("图片内容已损坏");
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw BusinessException.badRequest("图片内容已损坏");
        }
        return new Image(bytes, format.equals("jpeg") ? "jpg" : format, contentType);
    }
}
