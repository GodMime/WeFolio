package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 只包含虚构本桶身份的参考附件测试数据。 */
public final class PortfolioAiReferenceTestSupport {
    private PortfolioAiReferenceTestSupport() { }
    /** 构造完整可信条目，不含临时凭证。 */
    public static ObjectNode item(int number, boolean video) {
        String id = String.format("%032x", number);
        ObjectNode item = StrictAiJson.mapper().createObjectNode().put("attachmentId", id).put("referenceNo", "A" + number)
                .put("objectKey", "WFTEST/protfolio/ai-reference/" + id + (video ? ".mp4" : ".jpg"))
                .put("mediaType", video ? "VIDEO" : "IMAGE").put("originalFileName", video ? "参考.mp4" : "参考.jpg")
                .put("mimeType", video ? "video/mp4" : "image/jpeg").put("sizeBytes", video ? 100_000_000 : 4_000_000)
                .put("width", 4096).put("height", 2160).put("note", "参考留白");
        if (video) { item.put("durationMs", 300_000); } else { item.putNull("durationMs"); }
        item.putObject("contentVersion").put("etag", "version1").put("lastModifiedEpochMs", 1_700_000_000_000L);
        return item;
    }
    /** 两列共用条目，作品集额外携带编号高水位。 */
    public static String snapshot(boolean portfolio, JsonNode... items) {
        ObjectNode root = StrictAiJson.mapper().createObjectNode().put("version", 1);
        if (portfolio) { root.put("nextReferenceNo", 100); }
        var list = root.putArray("items"); for (JsonNode item : items) { list.add(item); }
        return root.toString();
    }
    /** 合法旧冻结输入，附件始终在独立列。 */
    public static ObjectNode request() {
        return StrictAiJson.parseObject("""
                {"contractVersion":"ai-portfolio-v1","promptVersion":"portfolio-ai-2026-10-10.2",
                 "input":{"ownerType":"USER","mode":"MODIFY_PAGE","targetMenuKey":"__root__","prompt":"参考A1"},
                 "facts":{"safePrompt":"调整留白","baseConfig":{"components":[]},"sources":[],"bindings":[]},
                 "modelConfig":{"provider":"TOKENHUB","model":"glm-5.3-flash","baseUrl":"https://tokenhub.tencentmaas.com/v1","maxTokens":8192}}
                """, 10000, 16);
    }
    /** 已领取的真实 owner 任务，在默认开关关闭时仍允许完成。 */
    public static AiGenerationTaskEntity task(JsonNode... items) {
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setId(1L); task.setOwnerType("USER"); task.setOwnerId(2L); task.setRequestedBy(2L);
        task.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))); task.setStatus("RUNNING");
        task.setVersion(1); task.setAttemptCount(1); task.setRequestJson(request().toString());
        task.setReferenceAttachmentsJson(snapshot(false, items)); return task;
    }
}
