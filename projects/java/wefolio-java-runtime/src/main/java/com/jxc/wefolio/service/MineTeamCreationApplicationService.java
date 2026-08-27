package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import com.jxc.wefolio.dto.MineTeamDetailResponse;
import com.jxc.wefolio.dto.MineTeamIdempotentCreateRequest;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** 团队创建应用服务，负责 Redis 幂等编排。 */
@Service
@RequiredArgsConstructor
public class MineTeamCreationApplicationService {

    /** 团队创建幂等记录有效期 */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    /** 团队创建缓存键前缀 */
    private static final String CACHE_KEY_PREFIX = "team:create:v2:";

    /** 团队创建分布式锁键前缀 */
    private static final String LOCK_KEY_PREFIX = "lock:team:create:v2:";

    /** 可打印 ASCII 幂等键格式 */
    private static final String IDEMPOTENCY_KEY_PATTERN = "^[\\x21-\\x7e]{1,64}$";

    /** 同键异参错误文案 */
    private static final String IDEMPOTENCY_CONFLICT_MESSAGE = "幂等键已用于不同的团队创建请求";

    /** 幂等键格式错误文案 */
    private static final String IDEMPOTENCY_KEY_INVALID_MESSAGE = "团队创建幂等键格式不正确";

    /** SHA-256 摘要算法 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 规范化字段分隔符 */
    private static final String FINGERPRINT_SEPARATOR = "\u0000";

    /** 团队业务服务 */
    private final MineTeamService mineTeamService;

    /** 团队 Mapper */
    private final TeamEntityMapper teamEntityMapper;

    /** 缓存服务 */
    private final CacheService cacheService;

    /** 分布式锁执行器 */
    private final DistributedLockExecutor lockExecutor;

    /**
     * 使用 Redis 幂等记录创建团队。
     *
     * @param request 带幂等键的创建请求
     * @return 新建或已存在的团队详情
     */
    public MineTeamDetailResponse create(MineTeamIdempotentCreateRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(
                request == null ? null : request.getIdempotencyKey());
        Long userId = AuthContextHolder.requireUserId();
        MineTeamService.PreparedTeamCreation prepared = mineTeamService.prepareTeamCreation(request);
        String fingerprint = fingerprint(prepared);
        String cacheKey = CACHE_KEY_PREFIX + userId + ":" + idempotencyKey;
        String lockKey = LOCK_KEY_PREFIX + userId + ":" + idempotencyKey;
        return lockExecutor.execute(lockKey,
                () -> createLocked(cacheKey, fingerprint, prepared));
    }

    /** 在同一用户与幂等键的分布式锁内完成创建或重放。 */
    private MineTeamDetailResponse createLocked(
            String cacheKey,
            String fingerprint,
            MineTeamService.PreparedTeamCreation prepared
    ) {
        TeamCreationIdempotencyRecord record = cacheService
                .get(cacheKey, TeamCreationIdempotencyRecord.class)
                .orElseGet(() -> reserve(cacheKey, fingerprint));
        if (!fingerprint.equals(record.getRequestFingerprint())) {
            throw new BusinessException(IDEMPOTENCY_CONFLICT_MESSAGE);
        }
        if (record.getTeamId() != null) {
            return mineTeamService.getTeamDetail(record.getTeamId());
        }

        TeamEntity persisted = findByUniqueCode(record.getUniqueCode());
        if (persisted != null) {
            return completeRecord(cacheKey, record, persisted.getId());
        }

        MineTeamDetailResponse created = mineTeamService.createPreparedTeam(prepared, record.getUniqueCode());
        Long teamId = created == null || created.getTeam() == null
                ? null
                : created.getTeam().getTeamId();
        if (teamId == null) {
            throw new BusinessException("团队创建失败，请重试");
        }
        record.setTeamId(teamId);
        cacheService.put(cacheKey, record, IDEMPOTENCY_TTL);
        return created;
    }

    /** 首次请求预留稳定团队唯一码。 */
    private TeamCreationIdempotencyRecord reserve(String cacheKey, String fingerprint) {
        TeamCreationIdempotencyRecord record = new TeamCreationIdempotencyRecord();
        record.setRequestFingerprint(fingerprint);
        record.setUniqueCode(mineTeamService.generateTeamUniqueCode());
        cacheService.put(cacheKey, record, IDEMPOTENCY_TTL);
        return record;
    }

    /** 把数据库中已存在的团队补写到 Redis 记录。 */
    private MineTeamDetailResponse completeRecord(
            String cacheKey,
            TeamCreationIdempotencyRecord record,
            Long teamId
    ) {
        record.setTeamId(teamId);
        cacheService.put(cacheKey, record, IDEMPOTENCY_TTL);
        return mineTeamService.getTeamDetail(teamId);
    }

    /** 按首次预留唯一码查询已经提交的团队。 */
    private TeamEntity findByUniqueCode(String uniqueCode) {
        return teamEntityMapper.selectOne(Wrappers.lambdaQuery(TeamEntity.class)
                .eq(TeamEntity::getUniqueCode, uniqueCode)
                .last("LIMIT 1"));
    }

    /** 规范化并校验客户端幂等键。 */
    private String normalizeIdempotencyKey(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches(IDEMPOTENCY_KEY_PATTERN)) {
            throw new BusinessException(IDEMPOTENCY_KEY_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 计算规范化创建字段的稳定 SHA-256 摘要。 */
    private String fingerprint(MineTeamService.PreparedTeamCreation prepared) {
        String canonical = prepared.name() + FINGERPRINT_SEPARATOR
                + prepared.intro() + FINGERPRINT_SEPARATOR + prepared.avatarUrl();
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }
}
