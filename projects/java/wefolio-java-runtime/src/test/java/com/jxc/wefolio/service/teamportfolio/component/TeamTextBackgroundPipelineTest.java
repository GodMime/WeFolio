package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import static org.mockito.Mockito.mock;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 团队普通文字背景新增契约，后续复用于结构化组件。 */
class TeamTextBackgroundPipelineTest {
    /** 背景关闭保存清理资源标识，但保留视觉偏好。 */
    @Test void normalizesClosedBackgroundAndRejectsMissingSelection() {
        TeamTextSectionComponentValidator validator = new TeamTextSectionComponentValidator(mock(TeamTextBackgroundSupport.class));
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(1,2,1);
        JSONObject input = JSON.parseObject("""
                {"content":"团队介绍","backgroundEnabled":false,"backgroundWorkId":11,"backgroundMemberUserId":7,
                 "backgroundTreatment":"ORIGINAL","verticalAlignment":"BOTTOM","backgroundWork":{"url":"injected"}}
                """);
        assertThat(validator.normalizeAndValidate(input,context)).containsEntry("backgroundEnabled",false)
                .containsEntry("backgroundTreatment","ORIGINAL").containsEntry("verticalAlignment","BOTTOM")
                .doesNotContainKeys("backgroundWorkId","backgroundMemberUserId","backgroundWork");
        input.put("backgroundEnabled",true); input.remove("backgroundWorkId");
        assertThatThrownBy(() -> validator.normalizeAndValidate(input,context)).isInstanceOf(BusinessException.class);
    }
}
