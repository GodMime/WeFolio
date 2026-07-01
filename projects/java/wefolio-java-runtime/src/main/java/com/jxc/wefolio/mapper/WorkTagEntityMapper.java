package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.entity.WorkTagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;


/**
 * 作品标签关联表 Mapper
 */
@Mapper
public interface WorkTagEntityMapper extends BaseMapper<WorkTagEntity> {

    /**
     * 批量更新当前用户指定标签下作品排序，调用方必须保证排序项非空。
     *
     * @param userId 当前用户 ID
     * @param tagId 标签 ID
     * @param items 排序项
     * @return 影响行数
     */
    @Update({
            "<script>",
            "UPDATE wf_work_tag",
            "SET sort_order = CASE work_id",
            "<foreach collection='items' item='item'>",
            "WHEN #{item.workId} THEN #{item.sortOrder}",
            "</foreach>",
            "END",
            "WHERE user_id = #{userId}",
            "AND tag_id = #{tagId}",
            "AND deleted = 0",
            "AND work_id IN",
            "<foreach collection='items' item='item' open='(' separator=',' close=')'>",
            "#{item.workId}",
            "</foreach>",
            "</script>"
    })
    int updateSortOrders(
            @Param("userId") Long userId,
            @Param("tagId") Long tagId,
            @Param("items") List<MineWorkSortRequest.Item> items
    );
}
