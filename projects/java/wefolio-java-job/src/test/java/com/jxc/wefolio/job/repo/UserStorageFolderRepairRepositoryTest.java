package com.jxc.wefolio.job.repo;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 历史用户目录修复仓储测试。
 */
class UserStorageFolderRepairRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void findActiveUsersAfterShouldUseIdCursorAndActiveNotDeletedFilter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserStorageFolderRepairRepository repository =
                new UserStorageFolderRepairRepository(jdbcTemplate);

        repository.findActiveUsersAfter(100L, 200);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.any(RowMapper.class),
                eq("ACTIVE"), eq(100L), eq(200));
        assertThat(sqlCaptor.getValue())
                .contains("status = ?", "deleted = 0", "id > ?", "ORDER BY id", "LIMIT ?")
                .doesNotContain("OFFSET");
    }
}
