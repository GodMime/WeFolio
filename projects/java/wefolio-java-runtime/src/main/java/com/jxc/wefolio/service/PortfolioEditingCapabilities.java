package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.PortfolioClientCapabilities;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentTraversal;
import java.util.HashSet;
import java.util.Set;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTimelineMessage;

/** 本次入口的有效编辑能力；持久配置与请求声明分开处理。 */
public record PortfolioEditingCapabilities(boolean supportsTimeline, boolean standardEntry) {
    /** 时间轴首次引入的个人结构版本。 */
    public static final int PERSONAL_TIMELINE_REVISION = 15;
    /** 时间轴首次引入的团队结构版本。 */
    public static final int TEAM_TIMELINE_REVISION = 11;
    /** 标准入口只能由显式声明授权时间轴。 */
    public static PortfolioEditingCapabilities standard(Integer revision, PortfolioClientCapabilities caps, boolean team) {
        boolean supported = caps != null && caps.supportsTimeline();
        requireRevision(revision, supported, team);
        return new PortfolioEditingCapabilities(supported, true);
    }
    /** 高级入口能力由已解析的高级版本决定。 */
    public static PortfolioEditingCapabilities advanced(Integer revision, int advancedVersion, boolean team) {
        boolean supported = advancedVersion >= 2;
        requireRevision(revision, supported, team);
        return new PortfolioEditingCapabilities(supported, false);
    }
    /** 发布和持久配置重验不模拟旧客户端。 */
    public static PortfolioEditingCapabilities stored() { return new PortfolioEditingCapabilities(true, false); }
    /** 旧标准请求只能回传已有时间轴身份，最终内容由兼容合并器恢复。 */
    public void requireAllowed(PortfolioConfigDto incoming, PortfolioConfigDto existing) {
        if (supportsTimeline) { return; }
        Set<String> saved = new HashSet<>();
        if (standardEntry) {
            for (var location : PortfolioComponentTraversal.listComponentLocations(existing)) {
                var component = location.component();
                if (component != null && PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(component.getComponentType())) { saved.add(component.getComponentKey()); }
            }
        }
        for (var location : PortfolioComponentTraversal.listComponentLocations(incoming)) {
            var component = location.component();
            if (component != null && PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(component.getComponentType()) && !saved.contains(component.getComponentKey())) {
                throw new BusinessException(PortfolioTimelineMessage.UNSUPPORTED_COMPONENT);
            }
        }
    }
    /** 团队采用相同的能力准入，不信任回传 revision 已经被提升。 */
    public void requireAllowed(TeamPortfolioConfigDto incoming, TeamPortfolioConfigDto existing) {
        if (supportsTimeline) { return; }
        Set<String> saved = new HashSet<>();
        if (standardEntry) {
            for (var location : TeamPortfolioComponentTraversal.listComponentLocations(existing)) {
                var component = location.component();
                if (component != null && PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(component.getComponentType())) { saved.add(component.getComponentKey()); }
            }
        }
        for (var location : TeamPortfolioComponentTraversal.listComponentLocations(incoming)) {
            var component = location.component();
            if (component != null && PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(component.getComponentType()) && !saved.contains(component.getComponentKey())) {
                throw new BusinessException(PortfolioTimelineMessage.UNSUPPORTED_COMPONENT);
            }
        }
    }
    /** 能力与结构版本必须同时满足。 */
    private static void requireRevision(Integer revision, boolean supported, boolean team) {
        if (supported && (revision == null || revision < (team ? TEAM_TIMELINE_REVISION : PERSONAL_TIMELINE_REVISION))) {
            throw new BusinessException(PortfolioTimelineMessage.INVALID_CAPABILITY);
        }
    }
}
