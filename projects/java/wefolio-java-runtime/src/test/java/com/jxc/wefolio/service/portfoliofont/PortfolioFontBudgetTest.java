package com.jxc.wefolio.service.portfoliofont;

import org.junit.jupiter.api.Test;
import org.apache.http.client.methods.HttpGet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/** 取消与成功退出都不能让进程树清理异常阻断连接关闭。 */
class PortfolioFontBudgetTest {
    /** 成功退出的进程无需枚举系统进程，活进程枚举失败仍终止父进程和连接。 */
    @Test void cleanupAlwaysClosesConnectionsAndTerminatesLiveParent() throws Exception {
        Process exited = mock(Process.class);
        try (var budget = new PortfolioFontBudget(1000)) { budget.attach(exited); }
        verify(exited, never()).descendants();
        Process live = mock(Process.class);
        when(live.isAlive()).thenReturn(true);
        when(live.descendants()).thenThrow(new UnsupportedOperationException());
        HttpGet request = new HttpGet("http://localhost/font");
        var budget = new PortfolioFontBudget(1000); budget.attach(live); budget.attach(request);
        assertThatCode(budget::close).doesNotThrowAnyException();
        verify(live).destroyForcibly();
        assertThat(request.isAborted()).isTrue();
    }
}
