package com.jxc.wefolio.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 带客户端幂等键的团队创建请求。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MineTeamIdempotentCreateRequest extends MineTeamCreateRequest {

    /** 客户端生成的团队创建幂等键 */
    private String idempotencyKey;
}
