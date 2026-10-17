package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.UserStorageUser;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.TeamStorageTeam;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 历史用户 COS 目录修复扫描仓储。
 */
@Repository
@RequiredArgsConstructor
public class UserStorageFolderRepairRepository {

    /** 有效用户状态 */
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 按用户 ID 游标读取有效未删除用户。
     *
     * @param afterUserId 起始用户 ID，不包含
     * @param limit 批大小
     * @return 当前页用户
     */
    public List<UserStorageUser> findActiveUsersAfter(long afterUserId, int limit) {
        String sql = """
                SELECT id, unique_code
                FROM wf_user
                WHERE status = ?
                  AND deleted = 0
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UserStorageUser(
                rs.getLong("id"),
                rs.getString("unique_code")
        ), ACTIVE_STATUS, afterUserId, limit);
    }

    /** 按独立团队 ID 游标读取有效未删除团队，不读取作品集内容。 */
    public List<TeamStorageTeam> findActiveTeamsAfter(long afterTeamId, int limit) {
        String sql = """
                SELECT id, unique_code
                FROM wf_team
                WHERE status = ?
                  AND deleted = 0
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TeamStorageTeam(
                rs.getLong("id"), rs.getString("unique_code")
        ), ACTIVE_STATUS, afterTeamId, limit);
    }

}
