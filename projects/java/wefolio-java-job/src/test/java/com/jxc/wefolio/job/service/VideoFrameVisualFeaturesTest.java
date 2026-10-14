package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** 视觉特征的定义与边界，不将梯度或差值哈希当作内容审核结论。 */
class VideoFrameVisualFeaturesTest {
    /** 单色与单像素图也必须返回合法边界，不发生除零。 */
    @Test
    void distinguishesDarkAndBrightFramesAndHandlesSinglePixel() {
        BufferedImage black = solid(1, 1, 0);
        BufferedImage white = solid(13, 11, 255);
        try {
            var dark = VideoFrameVisualFeatures.analyze(black);
            var bright = VideoFrameVisualFeatures.analyze(white);
            assertEquals("visual-features-v1", dark.version());
            assertEquals(0, dark.luminance());
            assertEquals(255, bright.luminance());
            assertEquals(0, dark.sharpness());
            assertEquals(0, bright.sharpness());
            assertEquals("0000000000000000", dark.dHash());
            assertEquals(dark.dHash(), bright.dHash());
        } finally { black.flush(); white.flush(); }
    }

    /** 相反方向的明暗梯度产生完全不同的六十四位哈希，输出保留前导零。 */
    @Test
    void differenceHashUsesHorizontalDirectionAndStableBitOrder() {
        BufferedImage ascending = new BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB);
        BufferedImage descending = new BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 9; x++) {
                    ascending.setRGB(x, y, gray(x * 30));
                    descending.setRGB(x, y, gray((8 - x) * 30));
                }
            }
            assertEquals("0000000000000000", VideoFrameVisualFeatures.analyze(ascending).dHash());
            assertEquals("ffffffffffffffff", VideoFrameVisualFeatures.analyze(descending).dHash());
            for (int y = 1; y < 8; y++) {
                for (int x = 0; x < 9; x++) { descending.setRGB(x, y, gray(100)); }
            }
            assertEquals("ff00000000000000", VideoFrameVisualFeatures.analyze(descending).dHash());
        } finally { ascending.flush(); descending.flush(); }
    }

    /** 黑白棋盘的每条相邻边都达到最大梯度，单色图为零，中间图保持范围。 */
    @Test
    void sharpnessMeasuresNormalizedAdjacentGradients() {
        BufferedImage checkerboard = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        BufferedImage mild = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    checkerboard.setRGB(x, y, gray((x + y) % 2 == 0 ? 0 : 255));
                    mild.setRGB(x, y, gray((x + y) % 2 == 0 ? 100 : 110));
                }
            }
            assertEquals(1000, VideoFrameVisualFeatures.analyze(checkerboard).sharpness());
            var features = VideoFrameVisualFeatures.analyze(mild);
            assertEquals(39, features.sharpness());
            assertEquals(105, features.luminance());
            assertTrue(features.dHash().matches("[0-9a-f]{16}"));
        } finally { checkerboard.flush(); mild.flush(); }
    }

    /** 创建无需文件系统的无透明通道单色图。 */
    private BufferedImage solid(int width, int height, int luminance) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { image.setRGB(x, y, gray(luminance)); }
        }
        return image;
    }

    /** 测试灰度值直接填入三个颜色通道。 */
    private int gray(int value) { return (value << 16) | (value << 8) | value; }
}
