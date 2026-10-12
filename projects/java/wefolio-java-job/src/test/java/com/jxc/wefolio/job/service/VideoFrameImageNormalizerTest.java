package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoFrameProperties;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** 内存图片规范化的字节和像素边界。 */
class VideoFrameImageNormalizerTest {
    private final VideoFrameProperties properties = new VideoFrameProperties();
    private final VideoFrameImageNormalizer normalizer = new VideoFrameImageNormalizer(properties);

    @Test
    void scalesToContractAndDoesNotEnlarge() throws Exception {
        var large = normalizer.normalize(image(1920, 1080));
        assertEquals(960, large.width()); assertEquals(540, large.height());
        assertTrue(large.bytes().length <= 200 * 1024);
        assertEquals(0xff, large.bytes()[0] & 0xff); assertEquals(0xd8, large.bytes()[1] & 0xff);
        assertEquals(960, ImageIO.read(new ByteArrayInputStream(large.bytes())).getWidth());
        var small = normalizer.normalize(image(64, 32));
        assertEquals(64, small.width()); assertEquals(32, small.height());
    }
    @Test
    void rejectsErrorBodyAndExcessPixelsBeforeFullDecode() throws Exception {
        assertThrows(VideoFrameProcessingException.class, () -> normalizer.normalize("<Error/>".getBytes()));
        properties.setMaxPixels(100);
        assertThrows(VideoFrameProcessingException.class, () -> normalizer.normalize(image(64, 32)));
    }
    @Test
    void calculatesFrontendCountIncludingNullAndNonPositive() {
        assertEquals(8, VideoFramePolicy.calculateFrameCount(null));
        assertEquals(8, VideoFramePolicy.calculateFrameCount(0));
        assertEquals(8, VideoFramePolicy.calculateFrameCount(-1));
        assertEquals(8, VideoFramePolicy.calculateFrameCount(23133));
        assertEquals(18, VideoFramePolicy.calculateFrameCount(180000));
        assertEquals(24, VideoFramePolicy.calculateFrameCount(600000));
        assertEquals(24, VideoFramePolicy.calculateFrameCount(Integer.MAX_VALUE));
    }
    /** 测试输入也明确采用纯内存编码。 */
    static byte[] image(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            ImageIO.write(image, "png", stream); stream.flush();
        } finally { image.flush(); }
        return output.toByteArray();
    }
}
