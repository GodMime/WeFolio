package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;

import java.util.List;
import java.util.Objects;

/** 统一解析用户唯一的未删除微信认证；历史多身份用户禁止继续资金操作。 */
public final class WechatPaymentIdentity {

    /** 仅需两个候选即可判定归属异常。 */
    private static final String QUERY_TWO = "LIMIT 2";

    /** 工具类不允许实例化。 */
    private WechatPaymentIdentity() {
    }

    /** 摘要密钥轮换不影响已保存的原始身份，不使用当前摘要重新判定旧身份。 */
    public static UserAuthEntity findUnique(UserAuthEntityMapper mapper, Long userId) {
        List<UserAuthEntity> candidates = mapper.selectList(Wrappers.lambdaQuery(UserAuthEntity.class)
                .eq(UserAuthEntity::getUserId, userId)
                .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                .last(QUERY_TWO));
        if (candidates.size() != 1) {
            return null;
        }
        UserAuthEntity auth = candidates.getFirst();
        return Objects.equals(userId, auth.getUserId())
                && AuthTypeDict.WECHAT_MINI_APP.getCode().equals(auth.getAuthType())
                && UserStatusDict.ACTIVE.getCode().equals(auth.getStatus())
                && (auth.getDeleted() == null || auth.getDeleted() == 0)
                && auth.getOpenId() != null && !auth.getOpenId().isBlank() ? auth : null;
    }

    /** session key 必须与业务用户和唯一认证同属一个身份。 */
    public static boolean matchesSession(Long userId, UserAuthEntity auth, MaintainerWechatSession session) {
        return auth != null && session != null && Objects.equals(userId, session.userId())
                && Objects.equals(userId, auth.getUserId()) && Objects.equals(auth.getId(), session.authId());
    }
}
