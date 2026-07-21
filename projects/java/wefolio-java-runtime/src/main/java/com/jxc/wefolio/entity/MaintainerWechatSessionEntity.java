package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.MaintainerWechatSessionStatusDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_maintainer_wechat_session — 维护者微信会话 — 加密保存虚拟支付结算所需会话。
 */
@Data
@TableName("wf_maintainer_wechat_session")
public class MaintainerWechatSessionEntity extends BaseEntity {

    /** 维护者用户 ID */
    private Long userId;

    /** 对应微信认证记录 ID */
    private Long authId;

    /** AES-GCM 加密后的 session_key */
    private String sessionKeyCiphertext;

    /** 每次刷新递增的会话版本 */
    private Long sessionVersion;

    /** 会话状态，取值见 {@link MaintainerWechatSessionStatusDict} */
    private String status;

    /** 服务端可信代理链解析的客户端 IP */
    private String lastUserIp;

    /** 最近刷新时间 */
    private LocalDateTime refreshedAt;

    /** 失效时间 */
    private LocalDateTime invalidatedAt;

    /** 脱敏失效原因 */
    private String invalidReason;
}
