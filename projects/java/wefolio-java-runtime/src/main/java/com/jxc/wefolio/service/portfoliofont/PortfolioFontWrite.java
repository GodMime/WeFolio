package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.message.PortfolioFontMessage;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.entity.PortfolioEntity;
import java.util.List;

/** 当前 HTTP 用例的字体准备与释放上下文，不持久化、不跨请求重用。 */
public final class PortfolioFontWrite {
    /** 个人与团队共用的字体根目录。 */
    private static final String FONT_STORAGE_FOLDER = "/others/fonts/";
    /** 事务内捕获的服务器快照副本。 */
    public PortfolioEntity snapshot;
    /** 兼容合并后的字体需求。 */
    public PortfolioFontPlan plan;
    /** 事务外完成的可信准备结果。 */
    public PortfolioFontService.Prepared prepared;
    /** 当前作品集的受控素材前缀。 */
    public String prefix;
    /** 本次成功提交释放的物理对象键。 */
    public List<String> released = List.of();

    /** 判断是否需要字体阶段，纯历史字体保持单事务快路径。 */
    public boolean capture(PortfolioEntity portfolio, PortfolioFontPlan candidate) {
        if (prepared != null || candidate.groups().isEmpty()
                && portfolio.getDraftFontAssetsJson() == null && portfolio.getPublishedFontAssetsJson() == null) { return false; }
        snapshot = JSON.parseObject(JSON.toJSONString(portfolio), PortfolioEntity.class);
        plan = candidate;
        return true;
    }

    /** 保存前后清单差集取锁内实际旧值。 */
    public void changed(PortfolioEntity current, String draft, String published) {
        released = PortfolioFontManifests.released(current.getDraftFontAssetsJson(), current.getPublishedFontAssetsJson(), draft, published);
    }

    /** 受控自有素材子目录按作品集隔离。 */
    public static String prefix(String uniqueCode, Long portfolioId) {
        if (uniqueCode == null || !uniqueCode.matches("[A-Za-z0-9_-]+") || portfolioId == null) {
            throw new IllegalStateException(PortfolioFontMessage.STORAGE_OWNER_MISSING);
        }
        return uniqueCode + FONT_STORAGE_FOLDER + portfolioId + "/";
    }
}
