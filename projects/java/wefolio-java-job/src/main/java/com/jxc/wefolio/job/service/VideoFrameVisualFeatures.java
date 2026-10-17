package com.jxc.wefolio.job.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * 视频帧最终规范尺寸 RGB 像素的轻量特征，在 JPEG 编码后的返回阶段计算，不重复解码。
 * 特征只辅助模型输入择优，不参与内容审核结论；有损编码造成的像素差异不重新分析。
 *
 * @param version 特征计算定义版本
 * @param dHash 九乘八缩略图的水平差值哈希，逐行最高位在前
 * @param luminance 全图平均亮度，范围为零至二百五十五
 * @param sharpness 相邻灰度差均值归一至零至一千，仅表示边缘细节强度
 */
public record VideoFrameVisualFeatures(String version, String dHash, int luminance, int sharpness) {
    /** 版本固定后不得改变已有字段的计算定义。 */
    public static final String VERSION = "visual-features-v1";
    /** 差值哈希每行八次比较，共六十四位。 */
    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;
    /** 灰度与梯度归一使用的固定上限。 */
    private static final int MAX_LUMINANCE = 255;
    private static final int MAX_SHARPNESS = 1000;
    /** BT.601 灰度权重的整数形式。 */
    private static final int RED_WEIGHT = 299;
    private static final int GREEN_WEIGHT = 587;
    private static final int BLUE_WEIGHT = 114;
    private static final int WEIGHT_SCALE = 1000;
    /** 六十四位哈希固定输出十六位小写十六进制。 */
    private static final String HASH_FORMAT = "%016x";

    /** 只遍历当前规范帧，除一行灰度及小缩略图外不保留额外像素副本。 */
    public static VideoFrameVisualFeatures analyze(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] previousRow = new int[width];
        long totalLuminance = 0;
        long totalGradient = 0;
        for (int y = 0; y < height; y++) {
            int left = 0;
            for (int x = 0; x < width; x++) {
                int gray = grayscale(image.getRGB(x, y));
                totalLuminance += gray;
                if (x > 0) { totalGradient += Math.abs(gray - left); }
                if (y > 0) { totalGradient += Math.abs(gray - previousRow[x]); }
                previousRow[x] = gray;
                left = gray;
            }
        }
        long pixels = (long) width * height;
        long adjacentPairs = (long) (width - 1) * height + (long) (height - 1) * width;
        int average = (int) ((totalLuminance + pixels / 2) / pixels);
        int detail = adjacentPairs == 0 ? 0
                : (int) Math.round((double) totalGradient * MAX_SHARPNESS / (adjacentPairs * MAX_LUMINANCE));
        return new VideoFrameVisualFeatures(VERSION, differenceHash(image), average, detail);
    }

    /** 缩至九乘八后逐行比较左像素是否更亮，保持确定的位顺序。 */
    private static String differenceHash(BufferedImage image) {
        BufferedImage thumbnail = new BufferedImage(HASH_WIDTH, HASH_HEIGHT, BufferedImage.TYPE_INT_RGB);
        try {
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, HASH_WIDTH, HASH_HEIGHT, null);
            } finally { graphics.dispose(); }
            long hash = 0;
            for (int y = 0; y < HASH_HEIGHT; y++) {
                for (int x = 0; x < HASH_WIDTH - 1; x++) {
                    hash = (hash << 1) | (grayscale(thumbnail.getRGB(x, y))
                            > grayscale(thumbnail.getRGB(x + 1, y)) ? 1 : 0);
                }
            }
            return String.format(Locale.ROOT, HASH_FORMAT, hash);
        } finally { thumbnail.flush(); }
    }

    /** 规范化图像已是无透明通道的 RGB，采用 BT.601 权重四舍五入。 */
    private static int grayscale(int rgb) {
        int red = (rgb >>> 16) & MAX_LUMINANCE;
        int green = (rgb >>> 8) & MAX_LUMINANCE;
        int blue = rgb & MAX_LUMINANCE;
        return (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue + WEIGHT_SCALE / 2) / WEIGHT_SCALE;
    }
}
