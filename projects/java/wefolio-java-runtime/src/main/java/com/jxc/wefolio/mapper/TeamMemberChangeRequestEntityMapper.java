package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.TeamMemberChangeRequestEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 团队成员信息变更确认请求表 Mapper。
 */
@Mapper
public interface TeamMemberChangeRequestEntityMapper extends BaseMapper<TeamMemberChangeRequestEntity> {
}
