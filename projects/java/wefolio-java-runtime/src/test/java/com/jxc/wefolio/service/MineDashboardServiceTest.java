package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MineDashboardResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointTransactionEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MineDashboardServiceTest {

    @Mock
    private UserEntityMapper userEntityMapper;

    @Mock
    private PointAccountEntityMapper pointAccountEntityMapper;

    @Mock
    private PointTransactionEntityMapper pointTransactionEntityMapper;

    @Mock
    private WorkEntityMapper workEntityMapper;

    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    @Mock
    private VisitRecordEntityMapper visitRecordEntityMapper;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void dashboardContainsProfilePointsMetricsAndLowBalanceWarning() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUniqueCode("MC-8392");
        user.setNickname("林安");
        user.setAvatarUrl("https://example.com/avatar.jpg");
        user.setProfession("婚礼司仪");
        user.setCity("上海");
        user.setProfileTags("[\"高端婚礼\",\"双语主持\"]");
        user.setStatus("ACTIVE");

        PointAccountEntity account = new PointAccountEntity();
        account.setUserId(7L);
        account.setBalance(42L);
        account.setTotalConsumed(300L);
        account.setTotalRecharged(1200L);

        PointTransactionEntity todayConsumption = new PointTransactionEntity();
        todayConsumption.setUserId(7L);
        todayConsumption.setPointsChange(-14L);
        todayConsumption.setOccurredAt(LocalDateTime.now());

        VisitRecordEntity firstVisit = new VisitRecordEntity();
        firstVisit.setVisitCount(3);
        VisitRecordEntity secondVisit = new VisitRecordEntity();
        secondVisit.setVisitCount(5);

        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(pointAccountEntityMapper.selectOne(any())).thenReturn(account);
        when(pointTransactionEntityMapper.selectList(any())).thenReturn(List.of(todayConsumption));
        when(workEntityMapper.selectCount(any())).thenReturn(36L);
        when(portfolioEntityMapper.selectCount(any())).thenReturn(4L);
        when(visitRecordEntityMapper.selectList(any())).thenReturn(List.of(firstVisit, secondVisit));

        MineDashboardService service = new MineDashboardService(
                userEntityMapper,
                pointAccountEntityMapper,
                pointTransactionEntityMapper,
                workEntityMapper,
                portfolioEntityMapper,
                visitRecordEntityMapper
        );

        MineDashboardResponse response = service.getDashboard();

        assertThat(response.getProfile().getUniqueCode()).isEqualTo("MC-8392");
        assertThat(response.getProfile().getDisplayName()).isEqualTo("林安 · 婚礼司仪");
        assertThat(response.getProfile().getTags()).containsExactly("高端婚礼", "双语主持");
        assertThat(response.getPoint().getBalance()).isEqualTo(42L);
        assertThat(response.getPoint().getTodayConsumed()).isEqualTo(14L);
        assertThat(response.getPoint().isLowBalance()).isTrue();
        assertThat(response.getMetrics().getWorkCount()).isEqualTo(36L);
        assertThat(response.getMetrics().getPublishedPortfolioCount()).isEqualTo(4L);
        assertThat(response.getMetrics().getRecentVisitCount()).isEqualTo(8L);
    }
}
