package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;

import java.time.Duration;
import java.util.List;

/** 一个模型调用的边界；任务重试由执行服务统一控制。 */
public interface PortfolioAiModelClient {
    /** 在绝对期限和响应字节预算内返回唯一 assistant 的 content。 */
    String generate(AiGenerationTaskEntity task, ObjectNode request, Duration remaining);

    /** 单次结果及真实 usage；未知 usage 不允许预算退款。 */
    record Completion(String content, Long promptTokens, Long completionTokens) { }
    /** 临时私有读取链接只在本次请求内存使用，禁止保存或记录。 */
    record MediaInput(String type, String url, Long timeMs) { }
    /** 新媒体任务最终文本生成，旧客户端仍可明确返回未知 usage。 */
    default Completion generateWithUsage(AiGenerationTaskEntity task, ObjectNode request, Duration remaining) {
        return new Completion(generate(task, request, remaining), null, null);
    }
    /** 独立媒体理解请求，不混合 image_url 与 video_url。 */
    default Completion summarize(AiGenerationTaskEntity task, ObjectNode request, List<MediaInput> media, Duration remaining) {
        throw new PortfolioAiCallException("CONTRACT_UNAVAILABLE", "多模态客户端不可用", false);
    }
    /** 完整最终文字请求字节上界，供费用保守预留。 */
    default int textRequestBytes(AiGenerationTaskEntity task, ObjectNode request) {
        return request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32768;
    }
}
