package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.MaintainerWechatSessionStatusDict;
import com.jxc.wefolio.entity.MaintainerWechatSessionEntity;
import com.jxc.wefolio.mapper.MaintainerWechatSessionEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 维护者微信会话服务 — 负责加密保存、只读提供和按版本条件失效。
 */
@Service
@RequiredArgsConstructor
public class MaintainerWechatSessionService {

    /** 维护者微信会话 Mapper。 */
    private final MaintainerWechatSessionEntityMapper maintainerWechatSessionEntityMapper;

    /** 会话密钥加密器。 */
    private final SessionKeyCipher sessionKeyCipher;

    /** Spring 事务事件发布器。 */
    private final ApplicationEventPublisher eventPublisher;

    /** 加密保存新会话，原子递增版本，并在提交后发布会话可用事件。 */
    @Transactional(rollbackFor = Exception.class)
    public MaintainerWechatSession saveAvailableSession(
            Long userId,
            Long authId,
            String sessionKey,
            String clientIp
    ) {
        if (userId == null || authId == null || sessionKey == null || sessionKey.isBlank()
                || clientIp == null || clientIp.isBlank()) {
            throw new IllegalArgumentException("维护者微信会话身份、密钥和客户端 IP 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = maintainerWechatSessionEntityMapper.upsertAvailable(
                userId,
                authId,
                sessionKeyCipher.encrypt(sessionKey),
                clientIp.strip(),
                now,
                MaintainerWechatSessionStatusDict.AVAILABLE.getCode()
        );
        if (updated <= 0) {
            throw new IllegalStateException("维护者微信会话保存失败");
        }
        MaintainerWechatSession available = findAvailableSession(userId);
        if (available == null) {
            throw new IllegalStateException("维护者微信会话保存后不可读取");
        }
        eventPublisher.publishEvent(new MaintainerWechatSessionAvailableEvent(userId));
        return available;
    }

    /** 查询并解密当前可用会话。 */
    @Transactional(readOnly = true)
    public MaintainerWechatSession findAvailableSession(Long userId) {
        MaintainerWechatSessionEntity entity = maintainerWechatSessionEntityMapper.selectOne(
                Wrappers.lambdaQuery(MaintainerWechatSessionEntity.class)
                        .eq(MaintainerWechatSessionEntity::getUserId, userId)
                        .eq(MaintainerWechatSessionEntity::getStatus,
                                MaintainerWechatSessionStatusDict.AVAILABLE.getCode())
                        .last("LIMIT 1")
        );
        if (entity == null || entity.getLastUserIp() == null || entity.getLastUserIp().isBlank()) {
            return null;
        }
        return new MaintainerWechatSession(
                entity.getUserId(),
                entity.getAuthId(),
                sessionKeyCipher.decrypt(entity.getSessionKeyCiphertext()),
                entity.getSessionVersion(),
                entity.getLastUserIp()
        );
    }

    /** 仅将微信明确拒绝的本次会话版本置为失效。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean invalidateVersion(Long userId, Long sessionVersion, String reason) {
        if (userId == null || sessionVersion == null) {
            return false;
        }
        return maintainerWechatSessionEntityMapper.invalidateVersion(
                userId,
                sessionVersion,
                normalizeReason(reason),
                LocalDateTime.now(),
                MaintainerWechatSessionStatusDict.AVAILABLE.getCode(),
                MaintainerWechatSessionStatusDict.INVALID.getCode()
        ) == 1;
    }

    /** 限制失效原因长度并避免保存空白详情。 */
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "微信会话已失效";
        }
        String normalized = reason.strip();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
