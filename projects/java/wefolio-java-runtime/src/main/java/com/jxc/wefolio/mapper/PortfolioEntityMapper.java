package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;


/**
 * 作品集表 Mapper
 */
@Mapper
public interface PortfolioEntityMapper extends BaseMapper<PortfolioEntity> {

    /** 所有字体接入与释放共同持有的作品集行锁。 */
    @Select("SELECT * FROM wf_portfolio WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    @Options(timeout = 5)
    PortfolioEntity lockById(@Param("id") Long id);

    /** 显式覆盖草稿字体清单，支持写入 SQL NULL。 */
    @Update("UPDATE wf_portfolio SET draft_font_assets_json = #{json} WHERE id = #{id} AND deleted = 0")
    int updateDraftFontAssets(@Param("id") Long id, @Param("json") String json);

    /** 显式覆盖发布字体清单，支持写入 SQL NULL。 */
    @Update("UPDATE wf_portfolio SET published_font_assets_json = #{json} WHERE id = #{id} AND deleted = 0")
    int updatePublishedFontAssets(@Param("id") Long id, @Param("json") String json);

    /** 删除作品集时清空两个字体快照，不影响历史逻辑配置。 */
    @Update("UPDATE wf_portfolio SET draft_font_assets_json = NULL, published_font_assets_json = NULL WHERE id = #{id} AND deleted = 0")
    int clearFontAssets(@Param("id") Long id);


    /** 当前用户有效标准个人作品集有序行锁 SQL。 */
    String LOCK_ACTIVE_STANDARD_PERSONAL_SQL = """
            SELECT *
            FROM wf_portfolio
            WHERE owner_type = 'USER'
              AND owner_id = #{ownerId}
              AND template_type = 'STANDARD'
              AND status = 'ACTIVE'
              AND deleted = 0
            ORDER BY id ASC
            FOR UPDATE
            """;

    /**
     * 按主键升序锁定当前用户全部有效标准个人作品集。
     *
     * @param ownerId 用户 ID
     * @return 已锁定作品集
     */
    @Select(LOCK_ACTIVE_STANDARD_PERSONAL_SQL)
    @Options(timeout = 5)
    List<PortfolioEntity> lockActiveStandardPersonalByOwnerId(@Param("ownerId") Long ownerId);
}
