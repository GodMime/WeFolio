package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;

import java.time.Duration;
import java.util.List;

/** 一个模型调用的边界；任务重试由执行服务统一控制。 */
public interface PortfolioAiModelClient {
    /** 在绝对期限和响应字节预算内返回唯一 assistant 的 content。 */
    String generate(AiGenerationTaskEntity task, ObjectNode request, Duration remaining);

    /**
     * 单次结果及真实用量；未知核心用量不允许预算退款，未知缓存量保留为 null。
     * @param content 唯一通过校验的模型正文
     * @param promptTokens 输入总量，包含缓存命中部分
     * @param completionTokens 输出总量，已包含思考用量
     * @param cachedTokens 合法缓存命中量；未知、异常或冲突时为 null
     * @param cacheStatus 缓存量采用或回退的受控诊断类别
     */
    record Completion(String content, Long promptTokens, Long completionTokens, Long cachedTokens, CacheStatus cacheStatus) {
        /** 兼容旧客户端及测试替身；未报告缓存量时按全价处理。 */
        public Completion(String content, Long promptTokens, Long completionTokens) {
            this(content, promptTokens, completionTokens, null, CacheStatus.UNKNOWN);
        }
    }
    /** 仅传递安全的缓存诊断分类，不保存供应商原始响应。 */
    enum CacheStatus {
        /** 已知缓存命中量大于零。 */
        KNOWN_HIT,
        /** 已知缓存命中量为零。 */
        KNOWN_MISS,
        /** 两个候选字段均未返回，缓存量未知。 */
        UNKNOWN,
        /** 已出现的候选字段或父节点不合法。 */
        INVALID,
        /** 两个合法候选字段的缓存量不一致。 */
        CONFLICT,
        /** 输入或输出核心用量不合法，不能结算已知费用。 */
        CORE_USAGE_UNKNOWN
    }
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
    /** 当前作品的独立冻结文字与媒体一起传递，禁止混入其它作品的音频。 */
    default Completion summarize(AiGenerationTaskEntity task, ObjectNode request, List<MediaInput> media,
                                 JsonNode entry, Duration remaining) {
        throw new PortfolioAiCallException("CONTRACT_UNAVAILABLE", "带音频文字的多模态客户端不可用", false);
    }
    /** 参考附件独立分析，不复用作品摘要缓存或作品编号语义。 */
    default Completion summarizeReferences(AiGenerationTaskEntity task, ObjectNode request, List<MediaInput> media,
                                           List<JsonNode> items, Duration remaining) {
        throw new PortfolioAiCallException("CONTRACT_UNAVAILABLE", "参考附件分析客户端不可用", false);
    }
    /** 完整最终文字请求字节上界，供费用保守预留。 */
    default int textRequestBytes(AiGenerationTaskEntity task, ObjectNode request) {
        return request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32768;
    }
}
