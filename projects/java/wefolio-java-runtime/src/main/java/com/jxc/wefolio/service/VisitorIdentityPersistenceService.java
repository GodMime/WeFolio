package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 访客身份持久化服务 — 仅在短事务内创建、刷新或恢复全局访客。
 */
@Service
@RequiredArgsConstructor
public class VisitorIdentityPersistenceService {

    /** 访客身份保存失败提示。 */
    private static final String VISITOR_PERSIST_FAILED_MESSAGE = "访客身份保存失败";

    /** 微信标识最大长度。 */
    private static final int WECHAT_IDENTIFIER_MAX_LENGTH = 128;

    /** 访客 key 随机字节数。 */
    private static final int VISITOR_KEY_RANDOM_BYTES = 16;

    /** 查询单条记录限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 安全随机数。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 访客数据访问器。 */
    private final VisitorEntityMapper visitorEntityMapper;

    /**
     * 尝试解析或创建访客身份。
     *
     * @param openid 微信 openid
     * @param unionid 微信 unionid
     * @return 已解析身份；并发插入被忽略时返回空
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<VisitorIdentity> tryResolveOrCreate(String openid, String unionid) {
        String normalizedOpenid = requireIdentifier(openid);
        String normalizedUnionid = normalizeOptionalIdentifier(unionid);
        VisitorEntity existing = findByOpenid(normalizedOpenid);
        if (existing != null) {
            refreshExisting(existing, normalizedUnionid);
            return Optional.of(new VisitorIdentity(existing, false));
        }

        VisitorEntity candidate = new VisitorEntity();
        candidate.setOpenid(normalizedOpenid);
        candidate.setUnionid(normalizedUnionid);
        candidate.setVisitorKey(randomHex(VISITOR_KEY_RANDOM_BYTES));
        candidate.setLastSeenAt(LocalDateTime.now());
        int inserted = visitorEntityMapper.insertIgnore(candidate);
        if (inserted == 0) {
            return Optional.empty();
        }
        if (inserted != 1 || candidate.getId() == null || candidate.getId() <= 0) {
            throw new BusinessException(VISITOR_PERSIST_FAILED_MESSAGE);
        }
        return Optional.of(new VisitorIdentity(candidate, true));
    }

    /**
     * 在首次短事务结束后恢复并发胜出的访客身份。
     *
     * @param openid 微信 openid
     * @param unionid 微信 unionid
     * @return 并发胜出的访客身份
     */
    @Transactional(rollbackFor = Exception.class)
    public VisitorIdentity recoverIgnoredInsert(String openid, String unionid) {
        String normalizedOpenid = requireIdentifier(openid);
        String normalizedUnionid = normalizeOptionalIdentifier(unionid);
        VisitorEntity winner = findByOpenid(normalizedOpenid);
        if (winner == null) {
            throw new BusinessException(VISITOR_PERSIST_FAILED_MESSAGE);
        }
        refreshExisting(winner, normalizedUnionid);
        return new VisitorIdentity(winner, false);
    }

    /** 按 openid 查询未删除访客。 */
    private VisitorEntity findByOpenid(String openid) {
        return visitorEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitorEntity.class)
                        .eq(VisitorEntity::getOpenid, openid)
                        .last(QUERY_LIMIT_ONE));
    }

    /** 刷新既有访客最近访问时间和缺失的 unionid。 */
    private void refreshExisting(VisitorEntity visitor, String unionid) {
        visitor.setLastSeenAt(LocalDateTime.now());
        if (hasText(unionid) && !hasText(visitor.getUnionid())) {
            visitor.setUnionid(unionid);
        }
        visitorEntityMapper.updateById(visitor);
    }

    /** 校验必填微信标识。 */
    private String requireIdentifier(String value) {
        if (!hasText(value)) {
            throw new BusinessException(VISITOR_PERSIST_FAILED_MESSAGE);
        }
        String normalized = value.strip();
        if (normalized.length() > WECHAT_IDENTIFIER_MAX_LENGTH) {
            throw new BusinessException(VISITOR_PERSIST_FAILED_MESSAGE);
        }
        return normalized;
    }

    /** 规范化可选微信标识。 */
    private String normalizeOptionalIdentifier(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > WECHAT_IDENTIFIER_MAX_LENGTH) {
            throw new BusinessException(VISITOR_PERSIST_FAILED_MESSAGE);
        }
        return normalized;
    }

    /** 判断文本是否非空。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 生成指定字节数的十六进制随机文本。 */
    private String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 已持久化访客身份。
     *
     * @param visitor 访客实体
     * @param newVisitor 是否本次创建
     */
    public record VisitorIdentity(VisitorEntity visitor, boolean newVisitor) {
    }
}
