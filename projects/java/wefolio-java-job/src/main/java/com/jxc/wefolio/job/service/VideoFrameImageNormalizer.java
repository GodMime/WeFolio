package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.IIOImage;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/** 逐张纯内存解码和 JPEG 规范化，不创建 ImageIO 临时文件。 */
@Service
@RequiredArgsConstructor
public class VideoFrameImageNormalizer {
    /** 持久图像长边及字节合同。 */
    public static final int MAX_EDGE = PortfolioAiMediaContract.FRAME_MAX_EDGE;
    /** 固定规范帧上限，不随原截图下载预算增加。 */
    public static final int MAX_BYTES = PortfolioAiMediaContract.FRAME_MAX_BYTES;
    /** 固定图片格式与失败阶段。 */
    private static final String JPEG_FORMAT = "jpeg";
    private static final String STAGE = "FRAME_NORMALIZE";
    /** 下载边界及历史处理预算配置。 */
    private final VideoFrameProperties properties;

    /** 规范化单张截图；不放大，过大像素在完整解码前拒绝。 */
    public NormalizedImage normalize(byte[] input) {
        if (input == null || input.length == 0 || input.length > properties.getMaxDownloadBytes()) {
            throw failure("截图字节为空或超过上限");
        }
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) { throw failure("截图不是可解码图片"); }
            ImageReader reader = readers.next();
            BufferedImage source;
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > properties.getMaxPixels()) {
                    throw failure("截图像素超过上限");
                }
                source = reader.read(0);
            } finally { reader.dispose(); }
            if (source == null) { throw failure("截图解码失败"); }
            double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.floor(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.floor(source.getHeight() * scale));
            try {
                while (true) {
                    BufferedImage target = resize(source, width, height);
                    try {
                        for (float quality : new float[] { 0.88f, 0.75f, 0.60f, 0.45f, 0.30f }) {
                            byte[] result = encode(target, quality);
                            if (result.length <= MAX_BYTES) {
                                return withOptionalFeatures(result, target);
                            }
                        }
                    } finally { target.flush(); }
                    if (Math.max(width, height) <= 128) { throw failure("截图无法压缩至字节上限"); }
                    width = Math.max(1, (int) (width * 0.8));
                    height = Math.max(1, (int) (height * 0.8));
                }
            } finally { source.flush(); }
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure("图片解码或 JPEG 编码失败"); }
    }
    /** 按比例缩小并使用白色填充透明区域。 */
    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return target;
    }
    /** 明确使用内存输出流，规避 ImageIO 的默认磁盘缓存。 */
    private byte[] encode(BufferedImage image, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(JPEG_FORMAT).next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
    /** 视觉特征失败仍保留基础帧；仅返回安全异常类型，交由持有作品与帧上下文的调用方记录。 */
    private NormalizedImage withOptionalFeatures(byte[] bytes, BufferedImage image) {
        try { return new NormalizedImage(bytes, image.getWidth(), image.getHeight(), analyzeFeatures(image)); }
        catch (RuntimeException exception) {
            return new NormalizedImage(bytes, image.getWidth(), image.getHeight(), null,
                    exception.getClass().getSimpleName());
        }
    }
    /** 独立的纯像素分析入口，调用方隔离辅助分析与必需图像规范化的失败。 */
    VideoFrameVisualFeatures analyzeFeatures(BufferedImage image) { return VideoFrameVisualFeatures.analyze(image); }
    /** 创建不包含原始响应的安全异常。 */
    private VideoFrameProcessingException failure(String reason) { return new VideoFrameProcessingException(STAGE, reason); }
    /** 仅保存当前一帧的规范化结果；失败类型不携带异常文案、原始图片或远端响应。 */
    public record NormalizedImage(byte[] bytes, int width, int height, VideoFrameVisualFeatures visualFeatures,
            String featureFailureType) {
        /** 成功提取特征的结果无需失败类型，并兼容原四参数调用方。 */
        public NormalizedImage(byte[] bytes, int width, int height, VideoFrameVisualFeatures visualFeatures) {
            this(bytes, width, height, visualFeatures, null);
        }
        /** 兼容不包含视觉特征的旧调用方与已有帧。 */
        public NormalizedImage(byte[] bytes, int width, int height) { this(bytes, width, height, null, null); }
    }
}
