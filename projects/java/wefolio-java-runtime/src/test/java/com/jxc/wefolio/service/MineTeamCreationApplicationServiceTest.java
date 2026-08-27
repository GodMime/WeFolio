package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamIdempotentCreateRequest;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 团队创建 Redis 幂等应用服务测试。 */
@ExtendWith(MockitoExtension.class)
class MineTeamCreationApplicationServiceTest {

    /** 团队业务服务模拟 */
    @Mock
    private MineTeamService mineTeamService;

    /** 团队 Mapper 模拟 */
    @Mock
    private TeamEntityMapper teamEntityMapper;

    /** 缓存服务模拟 */
    @Mock
    private CacheService cacheService;

    /** 分布式锁执行器模拟 */
    @Mock
    private DistributedLockExecutor lockExecutor;

    /** 内存中的 Redis 记录替身 */
    private final AtomicReference<TeamCreationIdempotencyRecord> cachedRecord = new AtomicReference<>();

    /** 每个测试建立当前用户认证上下文。 */
    @BeforeEach
    void setUp() {
        cachedRecord.set(null);
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    /** 测试结束后清理线程认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 安装会真实执行闭包的锁和内存缓存替身。 */
    private void stubRedisInfrastructure() {
        when(lockExecutor.execute(anyString(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(cacheService.get(anyString(), eq(TeamCreationIdempotencyRecord.class))).thenAnswer(invocation ->
                Optional.ofNullable(cachedRecord.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
            cachedRecord.set(invocation.getArgument(1));
            return null;
        }).when(cacheService).put(anyString(), any(TeamCreationIdempotencyRecord.class), any(Duration.class));
    }

    /** 首次创建写入 24 小时记录，成功重放不重复创建。 */
    @Test
    void firstCreationStoresRecordAndCompletedReplayReturnsExistingTeam() {
        stubRedisInfrastructure();
        MineTeamIdempotentCreateRequest request = request("team-create-1", "星曜司仪团");
        MineTeamService.PreparedTeamCreation prepared =
                new MineTeamService.PreparedTeamCreation("星曜司仪团", "团队简介", "");
        MineTeamDetailResponse created = response(100L);
        when(mineTeamService.prepareTeamCreation(request)).thenReturn(prepared);
        when(mineTeamService.generateTeamUniqueCode()).thenReturn("TMTEST0001");
        when(teamEntityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mineTeamService.createPreparedTeam(prepared, "TMTEST0001")).thenReturn(created);
        when(mineTeamService.getTeamDetail(100L)).thenReturn(created);

        MineTeamCreationApplicationService service = service();

        assertThat(service.create(request)).isSameAs(created);
        assertThat(service.create(request)).isSameAs(created);

        assertThat(cachedRecord.get().getUniqueCode()).isEqualTo("TMTEST0001");
        assertThat(cachedRecord.get().getTeamId()).isEqualTo(100L);
        verify(cacheService, times(2)).put(
                anyString(), any(TeamCreationIdempotencyRecord.class), eq(Duration.ofHours(24)));
        verify(mineTeamService, times(1)).createPreparedTeam(prepared, "TMTEST0001");
        verify(mineTeamService).getTeamDetail(100L);
    }

    /** 数据库提交后缓存未完成时，重试按预留唯一码恢复已有团队。 */
    @Test
    void retryFindsPersistedTeamByReservedUniqueCodeAfterInterruptedResponse() {
        stubRedisInfrastructure();
        MineTeamIdempotentCreateRequest request = request("team-create-2", "星曜司仪团");
        MineTeamService.PreparedTeamCreation prepared =
                new MineTeamService.PreparedTeamCreation("星曜司仪团", "团队简介", "");
        TeamEntity persisted = new TeamEntity();
        persisted.setId(101L);
        persisted.setUniqueCode("TMTEST0002");
        MineTeamDetailResponse restored = response(101L);
        when(mineTeamService.prepareTeamCreation(request)).thenReturn(prepared);
        when(mineTeamService.generateTeamUniqueCode()).thenReturn("TMTEST0002");
        when(teamEntityMapper.selectOne(any(Wrapper.class))).thenReturn(null, persisted);
        when(mineTeamService.createPreparedTeam(prepared, "TMTEST0002"))
                .thenThrow(new BusinessException("响应前中断"));
        when(mineTeamService.getTeamDetail(101L)).thenReturn(restored);
        MineTeamCreationApplicationService service = service();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("响应前中断");

        assertThat(service.create(request)).isSameAs(restored);
        assertThat(cachedRecord.get().getTeamId()).isEqualTo(101L);
        verify(mineTeamService, times(1)).createPreparedTeam(prepared, "TMTEST0002");
    }

    /** 相同键不得承载不同创建内容。 */
    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        stubRedisInfrastructure();
        MineTeamIdempotentCreateRequest first = request("team-create-3", "甲团队");
        MineTeamIdempotentCreateRequest changed = request("team-create-3", "乙团队");
        MineTeamService.PreparedTeamCreation firstPrepared =
                new MineTeamService.PreparedTeamCreation("甲团队", "团队简介", "");
        MineTeamService.PreparedTeamCreation changedPrepared =
                new MineTeamService.PreparedTeamCreation("乙团队", "团队简介", "");
        when(mineTeamService.prepareTeamCreation(first)).thenReturn(firstPrepared);
        when(mineTeamService.prepareTeamCreation(changed)).thenReturn(changedPrepared);
        when(mineTeamService.generateTeamUniqueCode()).thenReturn("TMTEST0003");
        when(teamEntityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mineTeamService.createPreparedTeam(firstPrepared, "TMTEST0003"))
                .thenThrow(new BusinessException("首次创建失败"));
        MineTeamCreationApplicationService service = service();

        assertThatThrownBy(() -> service.create(first)).hasMessage("首次创建失败");
        assertThatThrownBy(() -> service.create(changed))
                .isInstanceOf(BusinessException.class)
                .hasMessage("幂等键已用于不同的团队创建请求");
        verify(mineTeamService, never()).createPreparedTeam(changedPrepared, "TMTEST0003");
    }

    /** 幂等键必须是 1 至 64 个可打印 ASCII 字符。 */
    @Test
    void idempotencyKeyMustBePrintableAscii() {
        MineTeamCreationApplicationService service = service();

        assertThatThrownBy(() -> service.create(request("包含中文", "星曜司仪团")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队创建幂等键格式不正确");
        verify(mineTeamService, never()).prepareTeamCreation(any());
    }

    /** 构造被测服务。 */
    private MineTeamCreationApplicationService service() {
        return new MineTeamCreationApplicationService(
                mineTeamService, teamEntityMapper, cacheService, lockExecutor);
    }

    /** 构造创建请求。 */
    private MineTeamIdempotentCreateRequest request(String key, String name) {
        MineTeamIdempotentCreateRequest request = new MineTeamIdempotentCreateRequest();
        request.setIdempotencyKey(key);
        request.setName(name);
        request.setIntro("团队简介");
        request.setAvatarUrl("");
        return request;
    }

    /** 构造团队详情响应。 */
    private MineTeamDetailResponse response(Long teamId) {
        MineTeamDetailResponse response = new MineTeamDetailResponse();
        response.getTeam().setTeamId(teamId);
        return response;
    }
}
