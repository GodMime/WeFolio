package com.jxc.wefolio.service.teamportfolio.component.qrcontact;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队二维码联系组件引用提取器。
 */
@Component
public class TeamQrContactComponentReferenceExtractor {

    /** 二维码来源配置键。 */
    private static final String CONFIG_KEY_QR_URL_SOURCE = "qrUrlSource";

    /** 二维码地址配置键。 */
    private static final String CONFIG_KEY_QR_URL = "qrUrl";

    /** 组件键不能为空提示。 */
    private static final String COMPONENT_KEY_REQUIRED_MESSAGE = "二维码联系组件键不能为空";

    /** 组件路径不能为空提示。 */
    private static final String COMPONENT_PATH_REQUIRED_MESSAGE = "二维码联系组件路径不能为空";

    /** 有效引用值。 */
    private static final int VALID_REFERENCE = 1;

    /** 固定引用排序。 */
    private static final int REFERENCE_SORT_ORDER = 0;

    /** 二维码地址真实配置路径后缀。 */
    private static final String QR_URL_CONFIG_PATH_SUFFIX = ".config.qrUrl";

    /** 二维码联系渲染器。 */
    private final TeamQrContactComponentRenderer renderer;

    /**
     * 创建二维码联系引用提取器。
     *
     * @param renderer 二维码联系渲染器
     */
    public TeamQrContactComponentReferenceExtractor(TeamQrContactComponentRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 提取团队二维码资产引用。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件在配置中的路径
     * @param normalizedConfig 原始或归一化配置
     * @param context 团队作品集组件上下文
     * @return 唯一二维码资产引用
     */
    public List<PortfolioReferenceEntity> extract(String componentKey, String componentPath,
                                                   JSONObject normalizedConfig,
                                                   TeamPortfolioComponentContext context) {
        validateLocation(componentKey, componentPath);
        JSONObject renderedConfig = renderer.render(normalizedConfig, context);
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(context.portfolioId());
        reference.setReferenceType(ReferenceTypeDict.QR_CODE_ASSET.getCode());
        reference.setReferenceId(context.teamId());
        reference.setComponentKey(componentKey);
        reference.setComponentPath(componentPath + QR_URL_CONFIG_PATH_SUFFIX);
        reference.setSortOrder(REFERENCE_SORT_ORDER);
        reference.setIsValid(VALID_REFERENCE);
        reference.setSnapshotJson(createSnapshot(renderedConfig).toJSONString());
        return List.of(reference);
    }

    /**
     * 校验引用定位信息。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     */
    private void validateLocation(String componentKey, String componentPath) {
        if (componentKey == null || componentKey.isBlank()) {
            throw new BusinessException(COMPONENT_KEY_REQUIRED_MESSAGE);
        }
        if (componentPath == null || componentPath.isBlank()) {
            throw new BusinessException(COMPONENT_PATH_REQUIRED_MESSAGE);
        }
    }

    /**
     * 创建不包含个人资料数据的二维码引用快照。
     *
     * @param renderedConfig 已校验的渲染配置
     * @return 二维码引用快照
     */
    private JSONObject createSnapshot(JSONObject renderedConfig) {
        JSONObject snapshot = new JSONObject();
        snapshot.put(CONFIG_KEY_QR_URL_SOURCE, renderedConfig.getString(CONFIG_KEY_QR_URL_SOURCE));
        snapshot.put(CONFIG_KEY_QR_URL, renderedConfig.getString(CONFIG_KEY_QR_URL));
        return snapshot;
    }
}
