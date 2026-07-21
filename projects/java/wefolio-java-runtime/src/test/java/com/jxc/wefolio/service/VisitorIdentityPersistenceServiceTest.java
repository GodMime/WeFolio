package com.jxc.wefolio.service;

import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 访客身份短事务持久化测试。 */
@ExtendWith(MockitoExtension.class)
class VisitorIdentityPersistenceServiceTest {

    /** 访客数据访问器模拟。 */
    @Mock
    private VisitorEntityMapper visitorEntityMapper;

    @Test
    void mapperShouldDeclareSingleRowInsertIgnore() throws Exception {
        Method method = VisitorEntityMapper.class.getMethod("insertIgnore", VisitorEntity.class);
        Insert insert = method.getAnnotation(Insert.class);

        assertThat(insert).isNotNull();
        assertThat(String.join(" ", insert.value()))
                .contains("INSERT IGNORE INTO wf_visitor")
                .contains("openid", "unionid", "visitor_key", "last_seen_at");
    }

    @Test
    void remoteExchangeEntryShouldNotOwnTransaction() throws Exception {
        assertThat(VisitorService.class.getMethod("resolveByLoginCode", String.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(VisitorIdentityPersistenceService.class
                .getMethod("tryResolveOrCreate", String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(VisitorIdentityPersistenceService.class
                .getMethod("recoverIgnoredInsert", String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void ignoredInsertShouldReturnEmptyWithoutSecondQueryInSameTransaction() {
        when(visitorEntityMapper.selectOne(any())).thenReturn(null);
        when(visitorEntityMapper.insertIgnore(any())).thenReturn(0);

        Optional<VisitorIdentityPersistenceService.VisitorIdentity> result =
                service().tryResolveOrCreate("openid-a", "union-a");

        assertThat(result).isEmpty();
        verify(visitorEntityMapper, times(1)).selectOne(any());
    }

    @Test
    void successfulInsertShouldReturnNewVisitorWithGeneratedId() {
        when(visitorEntityMapper.selectOne(any())).thenReturn(null);
        when(visitorEntityMapper.insertIgnore(any())).thenAnswer(invocation -> {
            VisitorEntity visitor = invocation.getArgument(0);
            visitor.setId(7L);
            return 1;
        });

        VisitorIdentityPersistenceService.VisitorIdentity result = service()
                .tryResolveOrCreate("openid-a", "union-a")
                .orElseThrow();

        ArgumentCaptor<VisitorEntity> captor = ArgumentCaptor.forClass(VisitorEntity.class);
        verify(visitorEntityMapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getVisitorKey()).matches("[a-f0-9]{32}");
        assertThat(result.newVisitor()).isTrue();
        assertThat(result.visitor().getId()).isEqualTo(7L);
    }

    @Test
    void recoveryShouldReuseWinnerAndRefreshMissingUnionid() {
        VisitorEntity winner = new VisitorEntity();
        winner.setId(7L);
        winner.setOpenid("openid-a");
        winner.setVisitorKey("visitor-a");
        when(visitorEntityMapper.selectOne(any())).thenReturn(winner);

        VisitorIdentityPersistenceService.VisitorIdentity result =
                service().recoverIgnoredInsert("openid-a", "union-a");

        assertThat(result.newVisitor()).isFalse();
        assertThat(result.visitor()).isSameAs(winner);
        assertThat(winner.getUnionid()).isEqualTo("union-a");
        assertThat(winner.getLastSeenAt()).isNotNull();
        verify(visitorEntityMapper).updateById(winner);
    }

    @Test
    void recoveryShouldFailWhenIgnoredInsertHasNoOpenidWinner() {
        when(visitorEntityMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service().recoverIgnoredInsert("openid-a", "union-a"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访客身份保存失败");
    }

    /** 创建待测试服务。 */
    private VisitorIdentityPersistenceService service() {
        return new VisitorIdentityPersistenceService(visitorEntityMapper);
    }
}
