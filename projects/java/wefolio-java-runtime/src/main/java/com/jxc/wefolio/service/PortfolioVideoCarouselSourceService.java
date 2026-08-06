package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.PortfolioVideoCarouselWorkPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 个人作品集视频轮播候选来源服务。
 */
@Service
@RequiredArgsConstructor
public class PortfolioVideoCarouselSourceService {

    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页条数 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 作品服务 */
    private final MineWorkService mineWorkService;

    /**
     * 分页查询当前用户已审核通过的视频作品。
     *
     * <p>tagId 不传或为 null 时不过滤标签，与选择“全部”行为一致。</p>
     *
     * @param keyword 标题、原始文件名或标签关键字
     * @param tagId 可选作品标签 ID
     * @param page 页码
     * @param pageSize 每页条数
     * @return 视频候选分页
     */
    public PortfolioVideoCarouselWorkPageResponse page(
            String keyword,
            Long tagId,
            int page,
            int pageSize
    ) {
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        MineWorkListResponse source = mineWorkService.listWorks(
                keyword,
                tagId,
                MediaTypeDict.VIDEO.getCode(),
                WorkAuditStatusDict.PASSED.getCode(),
                normalizedPage,
                normalizedPageSize
        );
        return mapResponse(source, normalizedPage, normalizedPageSize);
    }

    /** 映射为视频轮播独立候选契约。 */
    private PortfolioVideoCarouselWorkPageResponse mapResponse(
            MineWorkListResponse source,
            int normalizedPage,
            int normalizedPageSize
    ) {
        PortfolioVideoCarouselWorkPageResponse response = new PortfolioVideoCarouselWorkPageResponse();
        response.setPage(source == null ? normalizedPage : source.getPage());
        response.setPageSize(source == null ? normalizedPageSize : source.getPageSize());
        response.setTotal(source == null ? 0L : source.getTotal());
        response.setHasMore(source != null && source.isHasMore());
        response.setFilterTags(safeList(source == null ? null : source.getTags()).stream()
                .map(this::mapFilterTag)
                .toList());
        response.setWorks(safeList(source == null ? null : source.getWorks()).stream()
                .map(this::mapWork)
                .toList());
        return response;
    }

    /** 映射标签筛选项。 */
    private PortfolioVideoCarouselWorkPageResponse.FilterTag mapFilterTag(MineWorkListResponse.TagItem source) {
        PortfolioVideoCarouselWorkPageResponse.FilterTag target =
                new PortfolioVideoCarouselWorkPageResponse.FilterTag();
        target.setTagId(source == null ? null : source.getId());
        target.setName(defaultString(source == null ? null : source.getName()));
        target.setColor(defaultString(source == null ? null : source.getColor()));
        target.setCount(source == null ? 0L : source.getCount());
        target.setActive(source != null && source.isActive());
        return target;
    }

    /** 映射视频作品项。 */
    private PortfolioVideoCarouselWorkPageResponse.WorkItem mapWork(MineWorkListResponse.WorkItem source) {
        PortfolioVideoCarouselWorkPageResponse.WorkItem target =
                new PortfolioVideoCarouselWorkPageResponse.WorkItem();
        target.setWorkId(source == null ? null : source.getId());
        target.setTitle(defaultString(source == null ? null : source.getTitle()));
        target.setMediaType(defaultString(source == null ? null : source.getMediaType()));
        target.setCoverUrl(defaultString(source == null ? null : source.getCoverUrl()));
        target.setMediaUrl(defaultString(source == null ? null : source.getMediaUrl()));
        target.setDurationMs(source == null ? null : source.getDurationMs());
        target.setWidth(source == null ? null : source.getWidth());
        target.setHeight(source == null ? null : source.getHeight());
        target.setAspectRatio(defaultString(source == null ? null : source.getAspectRatio()));
        target.setTags(safeList(source == null ? null : source.getTags()).stream()
                .map(this::mapTag)
                .toList());
        return target;
    }

    /** 映射作品标签。 */
    private PortfolioVideoCarouselWorkPageResponse.TagItem mapTag(MineWorkListResponse.TagItem source) {
        PortfolioVideoCarouselWorkPageResponse.TagItem target =
                new PortfolioVideoCarouselWorkPageResponse.TagItem();
        target.setTagId(source == null ? null : source.getId());
        target.setName(defaultString(source == null ? null : source.getName()));
        target.setColor(defaultString(source == null ? null : source.getColor()));
        return target;
    }

    /** 空列表兜底。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 空字符串兜底。 */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
