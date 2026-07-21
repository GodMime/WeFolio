package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.MaintainerWechatSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 维护者微信会话 Mapper。
 */
@Mapper
public interface MaintainerWechatSessionEntityMapper extends BaseMapper<MaintainerWechatSessionEntity> {

    /** 插入或原子递增维护者会话版本并更新可用会话。 */
    @Insert("""
            INSERT INTO wf_maintainer_wechat_session (
              user_id, auth_id, session_key_ciphertext, session_version, status,
              last_user_ip, refreshed_at, invalidated_at, invalid_reason,
              created_at, updated_at, deleted, version
            ) VALUES (
              #{userId}, #{authId}, #{ciphertext}, 1, #{availableStatus},
              #{clientIp}, #{refreshedAt}, NULL, NULL,
              #{refreshedAt}, #{refreshedAt}, 0, 0
            )
            ON DUPLICATE KEY UPDATE
              auth_id = VALUES(auth_id),
              session_key_ciphertext = VALUES(session_key_ciphertext),
              session_version = session_version + 1,
              status = VALUES(status),
              last_user_ip = VALUES(last_user_ip),
              refreshed_at = VALUES(refreshed_at),
              invalidated_at = NULL,
              invalid_reason = NULL,
              updated_at = VALUES(updated_at),
              version = version + 1
            """)
    int upsertAvailable(
            @Param("userId") Long userId,
            @Param("authId") Long authId,
            @Param("ciphertext") String ciphertext,
            @Param("clientIp") String clientIp,
            @Param("refreshedAt") LocalDateTime refreshedAt,
            @Param("availableStatus") String availableStatus
    );

    /** 仅失效微信明确拒绝的会话版本，不覆盖并发刷新的更高版本。 */
    @Update("""
            UPDATE wf_maintainer_wechat_session
               SET status = #{invalidStatus},
                   invalidated_at = #{invalidatedAt},
                   invalid_reason = #{reason},
                   updated_at = #{invalidatedAt},
                   version = version + 1
             WHERE user_id = #{userId}
               AND session_version = #{sessionVersion}
               AND status = #{availableStatus}
               AND deleted = 0
            """)
    int invalidateVersion(
            @Param("userId") Long userId,
            @Param("sessionVersion") Long sessionVersion,
            @Param("reason") String reason,
            @Param("invalidatedAt") LocalDateTime invalidatedAt,
            @Param("availableStatus") String availableStatus,
            @Param("invalidStatus") String invalidStatus
    );
}
