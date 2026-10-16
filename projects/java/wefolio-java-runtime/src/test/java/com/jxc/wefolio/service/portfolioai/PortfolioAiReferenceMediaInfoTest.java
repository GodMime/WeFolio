package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceProtocol.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 附件媒体信息的纯内存校验，要求完整客户端事实，不读写文件或远端对象。 */
class PortfolioAiReferenceMediaInfoTest {
    /** 客户端媒体类型与必填编码字段。 */
    private static final String PNG = "image/png", JPEG = "image/jpeg", MP4 = "video/mp4", CODEC = "codec";
    /** 测试中的独立对象大小和视频时长，避免仅重复产品上限。 */
    private static final long IMAGE_SIZE = 318_642L, VIDEO_SIZE = 3_814_642L, VIDEO_DURATION = 41_029L;

    /** 图片声明保留实际宽高和精确字节数，持久事实只包含原有六个字段。 */
    @Test
    void preservesImageFactsWithoutPersistingCodec() {
        for (String mime : List.of(PNG, JPEG)) {
            ObjectNode info = imageInfo(mime).put(CODEC, "");
            ObjectNode result = PortfolioAiReferenceMediaInfo.facts(info, mime, IMAGE_SIZE);
            assertFacts(result, mime, IMAGE, IMAGE_SIZE, 1200, 1800);
            assertThat(result.path(DURATION).isNull()).isTrue();
        }
    }

    /** MP4 支持两类编码及微信返回的别名，编码信息不进入持久事实。 */
    @Test
    void preservesVideoFactsForSupportedCodecAliases() {
        for (String codec : List.of("h264", "H.264", "avc1", "AVC1", "h265", "H.265", "hevc", "HEVC", "hvc1", "HEV1")) {
            ObjectNode result = PortfolioAiReferenceMediaInfo.facts(videoInfo().put(CODEC, codec), MP4, VIDEO_SIZE);
            assertFacts(result, MP4, VIDEO, VIDEO_SIZE, 888, 1920);
            assertThat(result.path(DURATION).asLong()).as(codec).isEqualTo(VIDEO_DURATION);
        }
    }

    /** 微信未识别出编码时必须明确上报空字符串，其余客户端事实仍按原值校验。 */
    @Test
    void acceptsExplicitlyEmptyCodec() {
        ObjectNode result = PortfolioAiReferenceMediaInfo.facts(videoInfo().put(CODEC, ""), MP4, VIDEO_SIZE);
        assertFacts(result, MP4, VIDEO, VIDEO_SIZE, 888, 1920);
        assertThat(result.path(DURATION).asLong()).isEqualTo(VIDEO_DURATION);
    }

    /** 缺失或显式为空的媒体信息必须拒绝，服务端不生成尺寸或时长占位值。 */
    @Test
    void rejectsMissingAndJsonNullMetadata() {
        for (JsonNode absent : new JsonNode[]{null, NODES.nullNode()}) {
            for (String mime : List.of(PNG, JPEG)) {
                assertRejected(absent, mime, IMAGE_SIZE);
            }
            assertRejected(absent, MP4, VIDEO_SIZE);
        }
    }

    /** 字节、图片边长和视频时长在明确上限处可通过，视频尺寸不套用图片边长上限。 */
    @Test
    void acceptsInclusiveLimits() {
        ObjectNode image = imageInfo(PNG).put(SIZE, 4_000_000L).put(WIDTH, 4096).put(HEIGHT, 4096);
        assertFacts(PortfolioAiReferenceMediaInfo.facts(image, PNG, 4_000_000L), PNG, IMAGE, 4_000_000L, 4096, 4096);
        ObjectNode video = videoInfo().put(SIZE, 100_000_000L).put(WIDTH, 7680).put(HEIGHT, 4320).put(DURATION, 300_000L);
        ObjectNode result = PortfolioAiReferenceMediaInfo.facts(video, MP4, 100_000_000L);
        assertFacts(result, MP4, VIDEO, 100_000_000L, 7680, 4320);
        assertThat(result.path(DURATION).asLong()).isEqualTo(300_000L);
    }

    /** 媒体信息必须是完整对象，非对象或空对象均不能作为有效声明。 */
    @Test
    void rejectsNonObjectAndEmptyMetadata() {
        for (JsonNode value : List.of(NODES.arrayNode(), NODES.textNode(""), NODES.numberNode(1), NODES.booleanNode(false), NODES.objectNode())) {
            assertRejected(value, MP4, VIDEO_SIZE);
        }
    }

    /** 六个字段都必须明确提供，包括空编码和图片的空时长。 */
    @Test
    void rejectsEachMissingRequiredField() {
        for (String field : List.of(MIME, CODEC, SIZE, WIDTH, HEIGHT, DURATION)) {
            ObjectNode image = imageInfo(PNG);
            image.remove(field);
            assertRejected(image, PNG, IMAGE_SIZE);
            ObjectNode video = videoInfo();
            video.remove(field);
            assertRejected(video, MP4, VIDEO_SIZE);
        }
    }

    /** 客户端不能自行增加服务端媒体分类、对象路径或其它未定义字段。 */
    @Test
    void rejectsUnknownFields() {
        for (String field : List.of(MEDIA, KEY, "unexpected")) {
            assertRejected(videoInfo().put(field, "unexpected"), MP4, VIDEO_SIZE);
        }
    }

    /** MIME 必须为与签名声明一致的字符串，不能通过隐式类型转换或换编码容器绕过。 */
    @Test
    void rejectsMimeMismatchUnsupportedMimeAndWrongTypes() {
        assertRejected(imageInfo(PNG), JPEG, IMAGE_SIZE);
        assertRejected(videoInfo().put(MIME, "video/quicktime"), MP4, VIDEO_SIZE);
        for (JsonNode value : List.of(NODES.nullNode(), NODES.numberNode(1), NODES.booleanNode(true), NODES.objectNode())) {
            ObjectNode info = videoInfo();
            info.set(MIME, value);
            assertRejected(info, MP4, VIDEO_SIZE);
        }
    }

    /** 大小必须是严格正整数、等于对象大小，并分别遵守图片与视频上限。 */
    @Test
    void rejectsInvalidSizeTypesMismatchAndOverflow() {
        for (JsonNode value : invalidPositiveIntegers(VIDEO_SIZE)) {
            ObjectNode info = videoInfo();
            info.set(SIZE, value);
            assertRejected(info, MP4, VIDEO_SIZE);
        }
        assertRejected(videoInfo().put(SIZE, VIDEO_SIZE + 1), MP4, VIDEO_SIZE);
        assertRejected(imageInfo(PNG).put(SIZE, 4_000_001L), PNG, 4_000_001L);
        assertRejected(videoInfo().put(SIZE, 100_000_001L), MP4, 100_000_001L);
    }

    /** 宽高不能为零、负数、字符串、小数或超过整数范围；图片最长边仍受限制。 */
    @Test
    void rejectsInvalidDimensionsAndOversizedImageEdges() {
        for (String field : List.of(WIDTH, HEIGHT)) {
            for (JsonNode value : invalidPositiveIntegers(888)) {
                ObjectNode info = videoInfo();
                info.set(field, value);
                assertRejected(info, MP4, VIDEO_SIZE);
            }
            assertRejected(videoInfo().put(field, (long) Integer.MAX_VALUE + 1), MP4, VIDEO_SIZE);
            assertRejected(imageInfo(PNG).put(field, 4097), PNG, IMAGE_SIZE);
        }
    }

    /** 视频时长必须是毫秒正整数且不超过五分钟，图片时长只接受显式空值。 */
    @Test
    void rejectsInvalidDurationTypesAndLimits() {
        for (JsonNode value : invalidPositiveIntegers(VIDEO_DURATION)) {
            ObjectNode video = videoInfo();
            video.set(DURATION, value);
            assertRejected(video, MP4, VIDEO_SIZE);
        }
        assertRejected(videoInfo().put(DURATION, 300_001L), MP4, VIDEO_SIZE);
        for (JsonNode value : List.of(NODES.numberNode(0), NODES.numberNode(1), NODES.textNode("null"), NODES.booleanNode(false))) {
            ObjectNode image = imageInfo(PNG);
            image.set(DURATION, value);
            assertRejected(image, PNG, IMAGE_SIZE);
        }
    }

    /** 编码必须是字符串且不能为 null，非空视频编码必须属于明确白名单。 */
    @Test
    void rejectsUnsupportedVideoCodecsAndWrongCodecTypes() {
        for (String codec : List.of("av1", "vp9", "mpeg4", "h264-extra")) {
            assertRejected(videoInfo().put(CODEC, codec), MP4, VIDEO_SIZE);
        }
        for (JsonNode value : List.of(NODES.nullNode(), NODES.numberNode(264), NODES.booleanNode(true), NODES.objectNode(), NODES.arrayNode())) {
            ObjectNode info = videoInfo();
            info.set(CODEC, value);
            assertRejected(info, MP4, VIDEO_SIZE);
            ObjectNode image = imageInfo(PNG);
            image.set(CODEC, value);
            assertRejected(image, PNG, IMAGE_SIZE);
        }
    }

    /** 构造与视频不同尺寸的图片声明，所有内容只存在测试内存。 */
    private static ObjectNode imageInfo(String mime) {
        return NODES.objectNode().put(MIME, mime).put(CODEC, "").put(SIZE, IMAGE_SIZE).put(WIDTH, 1200).put(HEIGHT, 1800).putNull(DURATION);
    }

    /** 构造完整竖屏短视频声明，未知编码以明确空字符串表示。 */
    private static ObjectNode videoInfo() {
        return NODES.objectNode().put(MIME, MP4).put(CODEC, "").put(SIZE, VIDEO_SIZE).put(WIDTH, 888).put(HEIGHT, 1920).put(DURATION, VIDEO_DURATION);
    }

    /** 数值即使表面等于合法值，也不允许字符串、小数及超长整数隐式截断。 */
    private static List<JsonNode> invalidPositiveIntegers(long equivalent) {
        return List.of(NODES.nullNode(), NODES.numberNode(0), NODES.numberNode(-1), NODES.textNode(Long.toString(equivalent)),
                NODES.numberNode((double) equivalent), NODES.numberNode(1.5), NODES.booleanNode(true), NODES.objectNode(),
                NODES.arrayNode(), NODES.numberNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
    }

    /** 检查事实值和封闭输出字段，避免客户端编码或扩展字段流入持久结构。 */
    private static void assertFacts(ObjectNode result, String mime, String media, long size, int width, int height) {
        assertThat(result.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder(MEDIA, MIME, SIZE, WIDTH, HEIGHT, DURATION);
        assertThat(result.path(MIME).asText()).isEqualTo(mime);
        assertThat(result.path(MEDIA).asText()).isEqualTo(media);
        assertThat(result.path(SIZE).asLong()).isEqualTo(size);
        assertThat(result.path(WIDTH).asInt()).isEqualTo(width);
        assertThat(result.path(HEIGHT).asInt()).isEqualTo(height);
    }

    /** 非法媒体声明统一抛出业务异常，不依赖网络、Spring 或文件系统。 */
    private static void assertRejected(JsonNode info, String mime, long objectSize) {
        assertThatThrownBy(() -> PortfolioAiReferenceMediaInfo.facts(info, mime, objectSize))
                .as("媒体声明 %s", info).isInstanceOf(BusinessException.class);
    }
}
