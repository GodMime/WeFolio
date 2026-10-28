package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTimelineMessage;
import com.jxc.wefolio.service.advancedportfolio.AdvancedVersionRegistry;

/** 组件目录查询的模式及能力边界，保持旧路由缺省为标准目录。 */
public final class PortfolioComponentLibraryRequest {
    /** 工具类不实例化。 */
    private PortfolioComponentLibraryRequest() { }

    /** 业务层解析声明，Controller 只绑定原始参数并委派。 */
    public static PortfolioEditingCapabilities capabilities(Integer revision, String templateType,
                                                            String timeline, String advancedVersion, boolean team) {
        String type = templateType == null ? PortfolioTemplateTypeDict.STANDARD.getCode() : templateType;
        if (PortfolioTemplateTypeDict.ADVANCED.getCode().equals(type)) {
            if (timeline != null) { throw invalid(); }
            int version = AdvancedVersionRegistry.queryVersion(advancedVersion);
            AdvancedVersionRegistry.requireEditor(version, team, revision);
            return PortfolioEditingCapabilities.advanced(revision, version, team);
        }
        if (!PortfolioTemplateTypeDict.STANDARD.getCode().equals(type) || advancedVersion != null) { throw invalid(); }
        PortfolioClientCapabilities capabilities = new PortfolioClientCapabilities();
        if (timeline != null) {
            if (!timeline.matches("[01]")) { throw invalid(); }
            capabilities.setPortfolioTimeline(Integer.valueOf(timeline));
        }
        return PortfolioEditingCapabilities.standard(revision, capabilities, team);
    }

    /** 模式与声明不匹配时不静默回退。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioTimelineMessage.INVALID_CAPABILITY); }
}
