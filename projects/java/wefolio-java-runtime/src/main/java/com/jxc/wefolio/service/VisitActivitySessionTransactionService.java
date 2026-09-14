package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dto.VisitActivityTrackingDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitActivitySessionEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.mapper.VisitActivitySessionEntityMapper;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.VisitActivityMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioVisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/** 活动会话事务边界；由应用服务经 Spring 代理调用，禁止事务自调用。 */
@Service
@RequiredArgsConstructor
public class VisitActivitySessionTransactionService {
    /** 单条查询限制。 */
    private static final String LIMIT_ONE = "LIMIT 1 FOR UPDATE";
    /** 服务端与客户端计时允许的一秒误差。 */
    private static final long CLOCK_TOLERANCE_MS = 1000L;
    /** 活动会话数据访问器。 */
    private final VisitActivitySessionEntityMapper sessionMapper;
    /** 访问汇总数据访问器。 */
    private final VisitRecordEntityMapper recordMapper;
    /** 打开事件数据访问器，用于校验旧全局幂等键归属。 */
    private final VisitEventEntityMapper eventMapper;
    /** 个人既有打开及积分计费流程。 */
    private final PortfolioVisitService personalVisitService;
    /** 团队既有打开流程。 */
    private final TeamPortfolioVisitService teamVisitService;

    /** 在同一事务内创建或恢复双键会话并完成首次打开；来源服务在事务外按既有顺序渲染。 */
    @Transactional(rollbackFor = Exception.class)
    public OpenResult open(PortfolioEntity portfolio, String portfolioType, Long visitorId, String visitorKey,
            String sourceType, String openKey, VisitActivityTrackingDto tracking) {
        if (sessionMapper.lockVisitor(visitorId) == null) {
            throw new BusinessException(VisitActivityMessage.SESSION_UNAVAILABLE);
        }
        VisitActivitySessionEntity session = sessionMapper.findOwnedForOpen(
                visitorId, portfolioType, portfolio.getId(), tracking.getClientSessionKey());
        if (session != null) {
            if (!Objects.equals(openKey, session.getOpenIdempotencyKey())) {
                throw new BusinessException(VisitActivityMessage.KEY_CONFLICT);
            }
            VisitRecordEntity record = requireOwnedRecord(session, portfolio, portfolioType, visitorId);
            return new OpenResult(record, session);
        }
        if (sessionMapper.findOpenKeyForOpen(visitorId, portfolioType, portfolio.getId(), openKey) != null) {
            throw new BusinessException(VisitActivityMessage.KEY_CONFLICT);
        }
        VisitRecordEntity record = PortfolioTypeDict.PERSONAL.getCode().equals(portfolioType)
                ? personalVisitService.recordOpen(portfolio, visitorId, visitorKey, sourceType, openKey)
                : teamVisitService.recordOpen(portfolio, visitorId, visitorKey, sourceType, openKey);
        // 唯一键冲突可发生于不同访客，写入后再次核验全局事件键，错误归属整体回滚。
        validateEventKey(openKey, portfolio, visitorKey);
        if (record == null || record.getId() == null || !Objects.equals(record.getVisitorId(), visitorId)
                || !Objects.equals(record.getPortfolioId(), portfolio.getId())
                || !Objects.equals(record.getPortfolioType(), portfolioType)) {
            throw new BusinessException(VisitActivityMessage.PERSISTENCE_FAILED);
        }
        session = new VisitActivitySessionEntity();
        session.setVisitorId(visitorId);
        session.setPortfolioId(portfolio.getId());
        session.setPortfolioType(portfolioType);
        session.setVisitRecordId(record.getId());
        session.setClientSessionKey(tracking.getClientSessionKey());
        session.setOpenIdempotencyKey(openKey);
        session.setActiveDurationMs(0L);
        session.setDeleted(0L);
        session.setVersion(0);
        VisitActivityTrackingDto.DeviceInfo device = tracking.getDevice();
        if (device != null) {
            session.setBrand(device.getBrand());
            session.setModel(device.getModel());
            session.setSystem(device.getSystem());
            session.setPlatform(device.getPlatform());
        }
        if (sessionMapper.insert(session) != 1 || session.getId() == null) {
            throw new BusinessException(VisitActivityMessage.PERSISTENCE_FAILED);
        }
        return new OpenResult(record, session);
    }

    /** 拒绝拿其他业务事件的全局打开键创建活动会话；不以该查询作为恢复依据。 */
    private void validateEventKey(String openKey, PortfolioEntity portfolio, String visitorKey) {
        VisitEventEntity event = eventMapper.selectOne(Wrappers.<VisitEventEntity>lambdaQuery()
                .eq(VisitEventEntity::getIdempotencyKey, openKey).last(LIMIT_ONE));
        if (event != null && (!Objects.equals(event.getPortfolioId(), portfolio.getId())
                || !Objects.equals(event.getVisitorKey(), visitorKey)
                || !Objects.equals(event.getEventType(), VisitEventTypeDict.PORTFOLIO_OPENED.getCode())
                || !Objects.equals(event.getOwnerType(), portfolio.getOwnerType())
                || !Objects.equals(event.getOwnerId(), portfolio.getOwnerId()))) {
            throw new BusinessException(VisitActivityMessage.KEY_CONFLICT);
        }
    }

    /** 恢复必须具有有效汇总及一致的服务端业务归属。 */
    private VisitRecordEntity requireOwnedRecord(VisitActivitySessionEntity session, PortfolioEntity portfolio,
            String portfolioType, Long visitorId) {
        VisitRecordEntity record = recordMapper.selectById(session.getVisitRecordId());
        if (record == null || !Objects.equals(record.getVisitorId(), visitorId)
                || !Objects.equals(record.getPortfolioId(), portfolio.getId())
                || !Objects.equals(record.getPortfolioType(), portfolioType)) {
            throw new BusinessException(VisitActivityMessage.SESSION_UNAVAILABLE);
        }
        return record;
    }

    /** 锁定会话后推进高水位；会话和访问汇总同事务提交，含首个零样本。 */
    @Transactional(rollbackFor = Exception.class)
    public long accept(Long portfolioId, String portfolioType, Long visitorId, Long sessionId, long incoming) {
        VisitActivitySessionEntity session = sessionMapper.lockOwned(sessionId, visitorId, portfolioType, portfolioId);
        if (session == null || session.getCreatedAt() == null) {
            throw new BusinessException(VisitActivityMessage.SESSION_UNAVAILABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        long elapsed = Math.max(0L, Duration.between(session.getCreatedAt(), now).toMillis());
        if (incoming < 0 || incoming > elapsed + CLOCK_TOLERANCE_MS) {
            throw new BusinessException(VisitActivityMessage.INVALID_DURATION);
        }
        long previous = session.getActiveDurationMs();
        long accepted = Math.max(previous, incoming);
        if (sessionMapper.accept(sessionId, previous, accepted, now) != 1
                || recordMapper.incrementForegroundDuration(session.getVisitRecordId(), accepted - previous) != 1) {
            throw new BusinessException(VisitActivityMessage.PERSISTENCE_FAILED);
        }
        return accepted;
    }

    /** 打开事务结果，基准取当前活动会话，不能取访问汇总时长。 */
    public record OpenResult(VisitRecordEntity record, VisitActivitySessionEntity session) { }
}
