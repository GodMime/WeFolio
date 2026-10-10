package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;

import java.time.Duration;

/** 一个模型调用的边界；任务重试由执行服务统一控制。 */
public interface PortfolioAiModelClient {
    /** 在绝对期限和响应字节预算内返回唯一 assistant 的 content。 */
    String generate(AiGenerationTaskEntity task, ObjectNode request, Duration remaining);
}
