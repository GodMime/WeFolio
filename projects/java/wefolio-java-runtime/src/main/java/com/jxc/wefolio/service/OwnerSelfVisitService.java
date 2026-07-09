package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 维护者本人访问识别服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerSelfVisitService {

    /** MySQL 单条限制片段 */
    private static final String SQL_SINGLE_LIMIT_CLAUSE = "LIMIT 1";

    /** 用户登录身份 openid 列名 */
    private static final String COLUMN_OPEN_ID = "open_id";

    /** 用户登录身份 Mapper */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /**
     * 判断当前访客 openid 是否属于当前作品集维护者本人。
     *
     * @param ownerId 作品集归属用户 ID
     * @param visitorOpenId 访客微信 openid
     * @param portfolioId 作品集 ID
     * @param visitorId 访客 ID
     * @param eventType 事件类型
     * @return 是否维护者本人访问
     */
    public boolean isOwnerSelfVisitor(
            Long ownerId,
            String visitorOpenId,
            Long portfolioId,
            Long visitorId,
            String eventType
    ) {
        if (ownerId == null || !hasText(visitorOpenId)) {
            return false;
        }
        String normalizedOpenId = visitorOpenId.strip();
        UserAuthEntity auth = userAuthEntityMapper.selectOne(
                Wrappers.<UserAuthEntity>query()
                        .eq(COLUMN_OPEN_ID, normalizedOpenId)
                        .last(SQL_SINGLE_LIMIT_CLAUSE)
        );
        if (auth == null || !Objects.equals(auth.getOpenId(), normalizedOpenId)
                || !Objects.equals(auth.getUserId(), ownerId)) {
            return false;
        }
        log.info("维护者本人访问作品集，跳过访客统计事件: ownerId={}, portfolioId={}, visitorId={}, eventType={}",
                ownerId, portfolioId, visitorId, eventType);
        return true;
    }

    /**
     * 判断文本是否非空。
     *
     * @param value 待判断文本
     * @return 是否包含非空白字符
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
