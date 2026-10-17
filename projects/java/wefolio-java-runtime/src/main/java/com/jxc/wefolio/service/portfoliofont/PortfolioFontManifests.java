package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import com.jxc.wefolio.dto.PortfolioFontManifestDto.Asset;
import com.jxc.wefolio.dto.PortfolioFontManifestDto.Unavailable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 字体清单投影与引用差集；历史快照本身不保活对象。 */
public final class PortfolioFontManifests {
    /** 经过最终产物校验并上传成功。 */
    public static final String READY = "READY";
    /** 无法提供当前需求。 */
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /** WOFF 格式标识。 */
    public static final String WOFF = "woff";
    /** 未保存固定版本。 */
    public static final String MISSING_VERSION = "MISSING_VERSION";
    /** 目录无法解析该固定版本。 */
    public static final String UNKNOWN_VERSION = "UNKNOWN_VERSION";
    /** 本次处理失败或已到预算。 */
    public static final String UNAVAILABLE_REASON = "FONT_UNAVAILABLE";

    /** 工具类不允许实例化。 */
    private PortfolioFontManifests() { }

    /** 读取持久化清单，旧数据 NULL 或损坏清单均只影响字体展示。 */
    public static PortfolioFontManifestDto parse(String json) {
        if (json == null || json.isBlank()) { return null; }
        try { return JSON.parseObject(json, PortfolioFontManifestDto.class); }
        catch (RuntimeException exception) { return null; }
    }

    /** 创建未生成需求清单；纯历史字体不生成清单。 */
    public static PortfolioFontManifestDto empty(PortfolioFontPlan plan) {
        if (plan.groups().isEmpty()) { return null; }
        PortfolioFontManifestDto result = new PortfolioFontManifestDto();
        result.setFormatVersion(1); result.setPlanVersion(PortfolioFontPlan.VERSION);
        result.setPlanHash(plan.hash()); result.setVersions(plan.versions());
        result.setAssets(new ArrayList<>()); result.setUnavailable(new ArrayList<>());
        return result;
    }

    /** 为不可用需求保留身份，绝不产生 URL。 */
    public static void unavailable(PortfolioFontManifestDto manifest, PortfolioFontPlan.Group group, String reason) {
        Unavailable item = new Unavailable();
        item.setFontId(group.fontId()); item.setFontVersion(group.fontVersion());
        item.setFontWeight(group.fontWeight()); item.setFontStyle(group.fontStyle());
        item.setStatus(UNAVAILABLE); item.setReason(reason);
        manifest.getUnavailable().add(item);
    }

    /** 只有当前计划与清单匹配时才能显示，移除服务端对象键。 */
    public static PortfolioFontManifestDto project(PortfolioFontPlan plan, String... candidates) {
        if (plan.groups().isEmpty()) { return null; }
        for (String json : candidates) {
            var source = parse(json);
            if (source == null || !Objects.equals(source.getPlanVersion(), PortfolioFontPlan.VERSION)
                    || !plan.hash().equals(source.getPlanHash())) { continue; }
            var result = empty(plan);
            for (var group : plan.groups()) {
                Asset found = find(group, source);
                if (found == null) { unavailable(result, group, reason(plan, group, source)); }
                else {
                    Asset copy = JSON.parseObject(JSON.toJSONString(found), Asset.class);
                    copy.setObjectKey(null); copy.setBucket(null);
                    result.getAssets().add(copy);
                }
            }
            return result;
        }
        var fallback = empty(plan);
        plan.groups().forEach(group -> unavailable(fallback, group, group.fontVersion() == null ? MISSING_VERSION : UNAVAILABLE_REASON));
        return fallback;
    }

    /** 仅同计划、同需求的服务端失败原因可跨投影保留。 */
    public static String reason(PortfolioFontPlan plan, PortfolioFontPlan.Group group,
                                PortfolioFontManifestDto... manifests) {
        if (group.fontVersion() == null) { return MISSING_VERSION; }
        for (var manifest : manifests) {
            if (manifest == null || !Objects.equals(manifest.getFormatVersion(), 1)
                    || !Objects.equals(manifest.getPlanVersion(), PortfolioFontPlan.VERSION)
                    || !Objects.equals(manifest.getPlanHash(), plan.hash()) || manifest.getUnavailable() == null) { continue; }
            for (Unavailable item : manifest.getUnavailable()) {
                if (item != null && UNAVAILABLE.equals(item.getStatus())
                        && Objects.equals(item.getFontId(), group.fontId())
                        && Objects.equals(item.getFontVersion(), group.fontVersion())
                        && Objects.equals(item.getFontWeight(), group.fontWeight())
                        && Objects.equals(item.getFontStyle(), group.fontStyle())
                        && UNKNOWN_VERSION.equals(item.getReason())) { return UNKNOWN_VERSION; }
            }
        }
        return UNAVAILABLE_REASON;
    }

    /** 同一作品集当前两列中查找完全匹配需求的可信产物。 */
    public static Asset find(PortfolioFontPlan.Group group, PortfolioFontManifestDto... manifests) {
        for (var manifest : manifests) {
            if (manifest == null || !Objects.equals(manifest.getFormatVersion(), 1) || !Objects.equals(manifest.getPlanVersion(), PortfolioFontPlan.VERSION) || manifest.getAssets() == null) { continue; }
            for (Asset asset : manifest.getAssets()) {
                if (asset != null && READY.equals(asset.getStatus()) && WOFF.equals(asset.getFormat())
                        && Objects.equals(group.fontWeight(), asset.getFontWeight()) && asset.getUrl() != null && asset.getObjectKey() != null
                        && Objects.equals(group.fontId(), asset.getFontId())
                        && Objects.equals(group.fontVersion(), asset.getFontVersion())
                        && Objects.equals(group.fontStyle(), asset.getFontStyle())
                        && Objects.equals(group.demandHash(), asset.getDemandHash())) { return asset; }
            }
        }
        return null;
    }

    /** 从实际提交前后两列快照取释放对象，去重单位是受控单桶的 objectKey。 */
    public static List<String> released(String oldDraft, String oldPublished, String newDraft, String newPublished) {
        Map<String, Asset> old = references(oldDraft, oldPublished);
        old.keySet().removeAll(references(newDraft, newPublished).keySet());
        return List.copyOf(old.keySet());
    }

    /** 只接受服务端清单中 READY 的物理对象引用。 */
    public static Map<String, Asset> references(String... snapshots) {
        Map<String, Asset> result = new LinkedHashMap<>();
        for (String json : snapshots) {
            var manifest = parse(json);
            if (manifest != null && manifest.getAssets() != null) {
                for (var asset : manifest.getAssets()) {
                    if (asset != null && READY.equals(asset.getStatus()) && asset.getObjectKey() != null) { result.put(asset.getBucket() + "|" + asset.getObjectKey(), asset); }
                }
            }
        }
        return result;
    }
}
