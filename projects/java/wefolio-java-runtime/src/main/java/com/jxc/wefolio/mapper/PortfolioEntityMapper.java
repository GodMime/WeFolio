package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;


/**
 * 作品集表 Mapper
 */
@Mapper
public interface PortfolioEntityMapper extends BaseMapper<PortfolioEntity> {

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
