package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.ContactLeadEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;


/**
 * 联系线索表 Mapper
 */
@Mapper
public interface ContactLeadEntityMapper extends BaseMapper<ContactLeadEntity> {

    /** 持有作品集行锁后当前读候选；最终由 Java 精确比较，避免数据库宽松排序误判。 */
    @Select("""
            SELECT * FROM wf_contact_lead
            WHERE portfolio_id = #{portfolioId} AND owner_type = #{ownerType}
              AND owner_id = #{ownerId} AND deleted = 0
              AND COALESCE(contact_name, '') = #{contactName}
              AND COALESCE(desired_schedule, '') = #{desiredSchedule}
              AND COALESCE(needs, '') = #{needs}
            ORDER BY id ASC FOR UPDATE
            """)
    List<ContactLeadEntity> findContentCandidatesForUpdate(
            @Param("portfolioId") Long portfolioId, @Param("ownerType") String ownerType,
            @Param("ownerId") Long ownerId, @Param("contactName") String contactName,
            @Param("desiredSchedule") String desiredSchedule, @Param("needs") String needs);

}
