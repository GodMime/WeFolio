package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.annotation.TimelineAnonymousAccess;
import com.jxc.wefolio.dto.VisitActivityUpdateRequest;
import com.jxc.wefolio.service.VisitorPortfolioService;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 活动 Controller 只验证注解、参数委派及响应包装。 */
class VisitActivitySessionControllerTest {
    /** 两端均要求访客认证并显式允许限定作用域朋友圈匿名访问。 */
    @Test
    void endpointsDelegateAndDeclareAccess() throws Exception {
        VisitorPortfolioService personal = mock(VisitorPortfolioService.class);
        VisitorTeamPortfolioService team = mock(VisitorTeamPortfolioService.class);
        VisitActivitySessionController controller = new VisitActivitySessionController(personal, team);
        VisitActivityUpdateRequest request = new VisitActivityUpdateRequest();request.setActiveDurationMs(BigDecimal.ZERO);
        when(personal.recordActivity("PF1", 1L, request)).thenReturn(45000L);
        when(team.recordActivity("TM1", 2L, request)).thenReturn(90000L);
        assertThat(controller.personal("PF1", 1L, request).getData()).isEqualTo(45000L);
        assertThat(controller.team("TM1", 2L, request).getData()).isEqualTo(90000L);
        for (String methodName : new String[]{"personal", "team"}) {
            var method = VisitActivitySessionController.class.getMethod(methodName, String.class, Long.class, VisitActivityUpdateRequest.class);
            assertThat(method.isAnnotationPresent(VisitorAccess.class)).isTrue();
            assertThat(method.isAnnotationPresent(TimelineAnonymousAccess.class)).isTrue();
            String path = methodName.equals("personal")
                    ? "/api/visitor/portfolios/{shareCode}/visit-sessions/{sessionId}/activity"
                    : "/api/visitor/team-portfolios/{shareCode}/visit-sessions/{sessionId}/activity";
            assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
            assertThat(method.getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
            assertThat(method.getParameters()[1].isAnnotationPresent(PathVariable.class)).isTrue();
            assertThat(method.getParameters()[2].isAnnotationPresent(RequestBody.class)).isTrue();
        }
    }
}
