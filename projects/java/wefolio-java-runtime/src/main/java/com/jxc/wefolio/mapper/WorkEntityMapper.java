package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.dto.MineWorkSortRequest;
import com.jxc.wefolio.entity.WorkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 作品表 Mapper
 */
@Mapper
public interface WorkEntityMapper extends BaseMapper<WorkEntity> {

    /**
     * 批量更新当前用户作品排序，调用方必须保证排序项非空。
     *
     * @param userId 当前用户 ID
     * @param items 排序项
     * @param updatedAt 应用层更新时间
     * @return 影响行数
     */
    @Update({
            "<script>",
            "UPDATE wf_work",
            "SET sort_order = CASE id",
            "<foreach collection='items' item='item'>",
            "WHEN #{item.workId} THEN #{item.sortOrder}",
            "</foreach>",
            "END, updated_at = #{updatedAt}",
            "WHERE user_id = #{userId}",
            "AND deleted = 0",
            "AND id IN",
            "<foreach collection='items' item='item' open='(' separator=',' close=')'>",
            "#{item.workId}",
            "</foreach>",
            "</script>"
    })
    int updateSortOrders(
            @Param("userId") Long userId,
            @Param("items") List<MineWorkSortRequest.Item> items,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
