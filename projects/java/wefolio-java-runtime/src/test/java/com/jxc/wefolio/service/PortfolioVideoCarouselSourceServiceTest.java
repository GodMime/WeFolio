package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dto.MineWorkListResponse;
import com.jxc.wefolio.dto.PortfolioVideoCarouselWorkPageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人视频轮播候选来源服务测试。
 */
@ExtendWith(MockitoExtension.class)
class PortfolioVideoCarouselSourceServiceTest {

    /** 作品服务模拟 */
    @Mock
    private MineWorkService mineWorkService;

    /**
     * 专用来源必须固定查询已审核视频，并把历史字段映射为独立契约。
     */
    @Test
    void pageShouldUseFixedVideoPassedFilterAndMapExactContract() {
        MineWorkListResponse source = new MineWorkListResponse();
        source.setPage(2);
        source.setPageSize(30);
        source.setTotal(41L);
        source.setHasMore(true);
        MineWorkListResponse.TagItem all = tag(null, "全部", "", 41L, false);
        MineWorkListResponse.TagItem wedding = tag(7L, "婚礼", "#F4A261", 12L, true);
        source.setTags(List.of(all, wedding));
        MineWorkListResponse.WorkItem work = new MineWorkListResponse.WorkItem();
        work.setId(88L);
        work.setTitle("草坪婚礼");
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setCoverUrl("https://cdn.example.com/cover.jpg");
        work.setMediaUrl("https://cdn.example.com/video.mp4");
        work.setDurationMs(18200);
        work.setWidth(1080);
        work.setHeight(1920);
        work.setAspectRatio("9:16");
        work.setTags(List.of(wedding));
        source.setWorks(List.of(work));
        when(mineWorkService.listWorks(
                "草坪",
                7L,
                MediaTypeDict.VIDEO.getCode(),
                WorkAuditStatusDict.PASSED.getCode(),
                2,
                30
        )).thenReturn(source);

        PortfolioVideoCarouselWorkPageResponse response = service().page("草坪", 7L, 2, 30);

        verify(mineWorkService).listWorks(
                "草坪",
                7L,
                MediaTypeDict.VIDEO.getCode(),
                WorkAuditStatusDict.PASSED.getCode(),
                2,
                30
        );
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getPageSize()).isEqualTo(30);
        assertThat(response.getTotal()).isEqualTo(41L);
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getFilterTags()).extracting(
                        PortfolioVideoCarouselWorkPageResponse.FilterTag::getTagId,
                        PortfolioVideoCarouselWorkPageResponse.FilterTag::getName,
                        PortfolioVideoCarouselWorkPageResponse.FilterTag::getColor,
                        PortfolioVideoCarouselWorkPageResponse.FilterTag::getCount,
                        PortfolioVideoCarouselWorkPageResponse.FilterTag::isActive)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(null, "全部", "", 41L, false),
                        org.assertj.core.groups.Tuple.tuple(7L, "婚礼", "#F4A261", 12L, true)
                );
        assertThat(response.getWorks()).singleElement().satisfies(item -> {
            assertThat(item.getWorkId()).isEqualTo(88L);
            assertThat(item.getTitle()).isEqualTo("草坪婚礼");
            assertThat(item.getMediaType()).isEqualTo(MediaTypeDict.VIDEO.getCode());
            assertThat(item.getCoverUrl()).isEqualTo("https://cdn.example.com/cover.jpg");
            assertThat(item.getMediaUrl()).isEqualTo("https://cdn.example.com/video.mp4");
            assertThat(item.getDurationMs()).isEqualTo(18200);
            assertThat(item.getWidth()).isEqualTo(1080);
            assertThat(item.getHeight()).isEqualTo(1920);
            assertThat(item.getAspectRatio()).isEqualTo("9:16");
            assertThat(item.getTags()).singleElement().satisfies(tag -> {
                assertThat(tag.getTagId()).isEqualTo(7L);
                assertThat(tag.getName()).isEqualTo("婚礼");
                assertThat(tag.getColor()).isEqualTo("#F4A261");
            });
        });
    }

    /**
     * 页码和分页大小必须有界，可选字符串与集合必须使用稳定空值语义。
     */
    @Test
    void pageShouldClampBoundariesAndNormalizeNullableFields() {
        MineWorkListResponse source = new MineWorkListResponse();
        source.setPage(1);
        source.setPageSize(100);
        source.setTotal(1L);
        source.setHasMore(false);
        source.setTags(null);
        MineWorkListResponse.WorkItem work = new MineWorkListResponse.WorkItem();
        work.setId(99L);
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setTags(null);
        source.setWorks(List.of(work));
        when(mineWorkService.listWorks(
                null,
                null,
                MediaTypeDict.VIDEO.getCode(),
                WorkAuditStatusDict.PASSED.getCode(),
                1,
                100
        )).thenReturn(source);

        PortfolioVideoCarouselWorkPageResponse response = service().page(null, null, 0, 1000);

        verify(mineWorkService).listWorks(
                null,
                null,
                MediaTypeDict.VIDEO.getCode(),
                WorkAuditStatusDict.PASSED.getCode(),
                1,
                100
        );
        assertThat(response.getFilterTags()).isEmpty();
        assertThat(response.getWorks()).singleElement().satisfies(item -> {
            assertThat(item.getTitle()).isEmpty();
            assertThat(item.getCoverUrl()).isEmpty();
            assertThat(item.getMediaUrl()).isEmpty();
            assertThat(item.getAspectRatio()).isEmpty();
            assertThat(item.getDurationMs()).isNull();
            assertThat(item.getWidth()).isNull();
            assertThat(item.getHeight()).isNull();
            assertThat(item.getTags()).isEmpty();
        });
    }

    private PortfolioVideoCarouselSourceService service() {
        return new PortfolioVideoCarouselSourceService(mineWorkService);
    }

    private MineWorkListResponse.TagItem tag(Long id, String name, String color, long count, boolean active) {
        MineWorkListResponse.TagItem tag = new MineWorkListResponse.TagItem();
        tag.setId(id);
        tag.setName(name);
        tag.setColor(color);
        tag.setCount(count);
        tag.setActive(active);
        return tag;
    }
}
