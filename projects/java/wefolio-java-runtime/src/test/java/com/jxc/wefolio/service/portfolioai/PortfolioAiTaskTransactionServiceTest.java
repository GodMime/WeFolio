package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 接单设施异常与真实活动额度不足应使用不同的安全失败语义。 */
@ExtendWith(MockitoExtension.class)
class PortfolioAiTaskTransactionServiceTest {
    /** 请求人及全局互斥行查询。 */
    @Mock private UserEntityMapper users;
    /** 任务查询与插入边界。 */
    @Mock private AiGenerationTaskEntityMapper tasks;
    /** 当前维护权校验。 */
    @Mock private PortfolioAiFactsService facts;
    /** 待测提交事务服务，真实事务竞争另由集成测试覆盖。 */
    private PortfolioAiTaskTransactionService service;

    /** 每个场景均先确认请求人有效。 */
    @BeforeEach void prepare() {
        service = new PortfolioAiTaskTransactionService(users, tasks, new PortfolioAiProperties(), facts);
        when(users.lockActiveUserById(101L)).thenReturn(101L);
    }

    /** 互斥行异常缺失时不误导用户取消不存在的活动任务，也不继续计数入队。 */
    @Test void missingAdmissionGuardReportsUnavailable() {
        when(users.lockAiAdmissionGuard()).thenReturn(null);
        assertThatThrownBy(() -> service.submit(101L, "new-task", frozen())).hasMessage(PortfolioAiMessage.DISABLED);
        verify(tasks, never()).selectCount(any());
        verify(tasks, never()).insert(any(AiGenerationTaskEntity.class));
    }

    /** 守卫正常而本人额度确实被占用时继续使用原活动任务提示。 */
    @Test void occupiedRequesterSlotStillReportsActiveTask() {
        when(users.lockAiAdmissionGuard()).thenReturn(1L);
        when(tasks.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.submit(101L, "new-task", frozen())).hasMessage(PortfolioAiMessage.ACTIVE_TASK);
        verify(tasks, never()).insert(any(AiGenerationTaskEntity.class));
    }

    /** 构造最小冻结身份，测试不包含外部配置和业务原文。 */
    private ObjectNode frozen() {
        return StrictAiJson.parseObject("{\"input\":{\"ownerType\":\"USER\",\"ownerId\":\"101\",\"portfolioId\":null}}", 1024, 4);
    }
}
