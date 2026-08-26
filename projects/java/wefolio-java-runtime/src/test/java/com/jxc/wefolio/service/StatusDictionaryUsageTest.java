package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dict.FeedbackUploadTaskStatusDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态字典使用规范测试 — 防止业务服务重新引入硬编码状态值。
 */
class StatusDictionaryUsageTest {

    /**
     * 维护者登录服务不应硬编码 ACTIVE 状态，应通过字典常量读取。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void miniappAuthServiceUsesStatusDictionaries() throws Exception {
        String source = readSource("src/main/java/com/jxc/wefolio/service/MiniappAuthService.java");

        assertThat(source).doesNotContain("\"ACTIVE\"");
    }

    /**
     * 我的首页服务不应硬编码 ACTIVE 状态，也不应使用实体完全限定类名。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void mineDashboardServiceUsesStatusDictionariesAndImportsEntities() throws Exception {
        String source = readSource("src/main/java/com/jxc/wefolio/service/MineDashboardService.java");

        assertThat(source).doesNotContain("\"ACTIVE\"");
        assertThat(source).doesNotContain("Wrappers.lambdaQuery(com.jxc.wefolio.entity.WorkEntity.class)");
        assertThat(source).doesNotContain("Wrappers.lambdaQuery(com.jxc.wefolio.entity.PortfolioEntity.class)");
        assertThat(source).doesNotContain(".eq(com.jxc.wefolio.entity.WorkEntity::");
        assertThat(source).doesNotContain(".eq(com.jxc.wefolio.entity.PortfolioEntity::");
    }

    /**
     * 我的作品服务不应硬编码作品集配置作用域，应通过字典常量读取。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void mineWorkServiceUsesPortfolioConfigScopeDictionary() throws Exception {
        String source = readSource("src/main/java/com/jxc/wefolio/service/MineWorkService.java");

        assertThat(source).doesNotContain("\"PUBLISHED\"");
    }

    /**
     * 档期状态色调属于前后端约定，应由字典统一承载。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void scheduleStatusToneComesFromDictionary() throws Exception {
        String source = readSource("src/main/java/com/jxc/wefolio/service/MineScheduleService.java");

        assertThat(ScheduleStatusDict.fromCode("AVAILABLE")).isNull();
        assertThat(ScheduleStatusDict.BOOKED.getTone()).isEqualTo("rose");
        assertThat(ScheduleStatusDict.TENTATIVE.getTone()).isEqualTo("amber");
        assertThat(ScheduleStatusDict.REST.getTone()).isEqualTo("muted");
        assertThat(source).doesNotContain("return \"teal\"");
        assertThat(source).doesNotContain("return \"rose\"");
        assertThat(source).doesNotContain("return \"amber\"");
        assertThat(source).doesNotContain("return \"muted\"");
    }

    /**
     * 意见反馈状态、媒体类型与上传任务状态应由字典统一承载。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void feedbackDictionariesDeclareCompleteMappingsAndRejectUnknownCodes() {
        assertDictionaryValue(FeedbackStatusDict.PROCESSING, "PROCESSING", "处理中");
        assertDictionaryValue(FeedbackStatusDict.WAITING_FOLLOW_UP, "WAITING_FOLLOW_UP", "待再次反馈");
        assertDictionaryValue(FeedbackStatusDict.RESOLVED, "RESOLVED", "已处理");
        assertThat(FeedbackStatusDict.fromCode(null)).isNull();
        assertThat(FeedbackStatusDict.fromCode("UNKNOWN")).isNull();

        assertDictionaryValue(FeedbackMediaTypeDict.IMAGE, "IMAGE", "图片");
        assertDictionaryValue(FeedbackMediaTypeDict.VIDEO, "VIDEO", "视频");
        assertThat(FeedbackMediaTypeDict.fromCode(null)).isNull();
        assertThat(FeedbackMediaTypeDict.fromCode("UNKNOWN")).isNull();

        assertDictionaryValue(FeedbackUploadTaskStatusDict.PENDING, "PENDING", "待上传");
        assertDictionaryValue(FeedbackUploadTaskStatusDict.CONFIRMED, "CONFIRMED", "已确认");
        assertDictionaryValue(FeedbackUploadTaskStatusDict.EXPIRED, "EXPIRED", "已过期");
        assertThat(FeedbackUploadTaskStatusDict.fromCode(null)).isNull();
        assertThat(FeedbackUploadTaskStatusDict.fromCode("UNKNOWN")).isNull();
    }

    /**
     * 验证意见反馈处理状态的完整映射。
     *
     * @param value 状态字典值
     * @param code 预期编码
     * @param displayName 预期展示名称
     */
    private void assertDictionaryValue(FeedbackStatusDict value, String code, String displayName) {
        assertThat(value.getCode()).isEqualTo(code);
        assertThat(value.getDisplayName()).isEqualTo(displayName);
        assertThat(FeedbackStatusDict.fromCode(code)).isSameAs(value);
    }

    /**
     * 验证意见反馈媒体类型的完整映射。
     *
     * @param value 媒体类型字典值
     * @param code 预期编码
     * @param displayName 预期展示名称
     */
    private void assertDictionaryValue(FeedbackMediaTypeDict value, String code, String displayName) {
        assertThat(value.getCode()).isEqualTo(code);
        assertThat(value.getDisplayName()).isEqualTo(displayName);
        assertThat(FeedbackMediaTypeDict.fromCode(code)).isSameAs(value);
    }

    /**
     * 验证意见反馈上传任务状态的完整映射。
     *
     * @param value 上传任务状态字典值
     * @param code 预期编码
     * @param displayName 预期展示名称
     */
    private void assertDictionaryValue(FeedbackUploadTaskStatusDict value, String code, String displayName) {
        assertThat(value.getCode()).isEqualTo(code);
        assertThat(value.getDisplayName()).isEqualTo(displayName);
        assertThat(FeedbackUploadTaskStatusDict.fromCode(code)).isSameAs(value);
    }

    /**
     * 读取项目源码文件。
     *
     * @param path 源码相对路径
     * @return 源码内容
     * @throws Exception 读取源码失败时抛出异常
     */
    private String readSource(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
