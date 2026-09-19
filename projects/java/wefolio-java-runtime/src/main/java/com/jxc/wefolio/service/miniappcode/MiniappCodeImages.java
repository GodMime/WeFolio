package com.jxc.wefolio.service.miniappcode;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMiniappCodeImageMessage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** 在分配像素内存前验证受控图片的格式和尺寸。 */
final class MiniappCodeImages {
    /** 支持且部署环境无需额外解码插件的格式。 */
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "jpg");
    /** 单张图片最大边长。 */
    private static final int MAX_DIMENSION = 4096;
    /** 单张图片最大像素数。 */
    private static final long MAX_PIXELS = 16_000_000;
    /** 最大输入体积。 */
    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** 工具类不允许实例化。 */
    private MiniappCodeImages() { }
    /** 安全解码，拒绝动画、未知格式和超大像素图片。 */
    static BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))
                        || width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) throw new IOException();
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
        }
    }
}
