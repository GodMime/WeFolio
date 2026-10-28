package com.jxc.wefolio.dto;

import lombok.Data;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jxc.wefolio.exception.InvalidPortfolioCapabilityException;

/** 客户端明确实现的作品集扩展能力，不从配置继承。 */
@Data
public class PortfolioClientCapabilities {
    /** 远程字体协议版本，当前为 1。 */
    private Integer portfolioRemoteFont;
    /** 标准入口时间轴能力；缺省与显式零参与不同的幂等投影。 */
    private Integer portfolioTimeline;
    /** 保留声明是否出现，禁止把缺省补入历史请求。 */
    @JSONField(serialize = false, deserialize = false)
    @JsonIgnore
    private boolean timelineProvided;

    /** 禁止 JSON 解析器将字符串、布尔、小数或 null 转成能力整数。 */
    public void setPortfolioTimeline(Object value) {
        timelineProvided = true;
        if (!(value instanceof Integer number) || number < 0 || number > 1) {
            throw new InvalidPortfolioCapabilityException();
        }
        portfolioTimeline = number;
    }

    /** 只有显式声明版本一才能编辑时间轴。 */
    public boolean supportsTimeline() { return Integer.valueOf(1).equals(portfolioTimeline); }

    /** 供标准保存指纹保留缺省与显式声明区别。 */
    public boolean hasTimelineIntent() { return timelineProvided; }

    /** 只接受当前实现的能力版本。 */
    public boolean supportsRemoteFonts() {
        return Integer.valueOf(1).equals(portfolioRemoteFont);
    }
}
