package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;

/**
 * 新旧编辑器配置兼容合并器。
 * <p>
 * 当旧维护端（不携带 {@code editorSchemaRevision} 或版本低于当前值）保存作品集时，
 * 合并器读取服务端已有草稿的新字段，避免 {@code style}、{@code bottomNav} 等
 * 新编辑器能力被旧客户端静默清除。
 * <p>
 * 本类从 {@link TeamPortfolioConfigValidator} 提取，保持原有合并契约不变。
 */
public final class TeamPortfolioConfigMerger {

    /**
     * 工具类不允许实例化。
     */
    private TeamPortfolioConfigMerger() {
    }

    /**
     * 合并本次请求配置与服务端已有草稿。
     * <ul>
     *   <li>新编辑器请求（revision ≥ 2）：以请求字段为准。</li>
     *   <li>旧编辑器请求（revision 缺失或 &lt; 2）：以请求更新顶层组件和分享信息，
     *       但保留已有草稿的 {@code editorSchemaRevision}、{@code style}、{@code bottomNav}。</li>
     * </ul>
     *
     * @param incomingConfig      本次请求配置
     * @param existingDraftConfig 服务端当前草稿配置，首次创建时为 {@code null}
     * @return 合并后的配置，不包含组件业务校验
     */
    public static TeamPortfolioConfigDto merge(
            TeamPortfolioConfigDto incomingConfig,
            TeamPortfolioConfigDto existingDraftConfig
    ) {
        TeamPortfolioConfigDto merged = new TeamPortfolioConfigDto();
        merged.setSchemaVersion(incomingConfig.getSchemaVersion());
        merged.setShare(incomingConfig.getShare());
        merged.setComponents(incomingConfig.getComponents());

        Integer incomingRevision = incomingConfig.getEditorSchemaRevision();
        Integer existingRevision = existingDraftConfig == null
                ? null
                : existingDraftConfig.getEditorSchemaRevision();
        boolean legacyIncoming = incomingRevision == null
                || incomingRevision < TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT;
        boolean existingHasCurrentFields = existingRevision != null
                && existingRevision >= TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT;
        merged.setEditorSchemaRevision(
                legacyIncoming && existingHasCurrentFields ? existingRevision : incomingRevision);
        if (!legacyIncoming) {
            merged.setStyle(incomingConfig.getStyle());
            merged.setBottomNav(incomingConfig.getBottomNav());
        } else if (existingHasCurrentFields) {
            merged.setStyle(existingDraftConfig.getStyle());
            merged.setBottomNav(existingDraftConfig.getBottomNav());
        }
        return merged;
    }
}
