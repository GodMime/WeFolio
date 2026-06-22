package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.UserAuthEntity;
import org.apache.ibatis.annotations.Mapper;


/**
 * 用户登录身份表 Mapper
 */
@Mapper
public interface UserAuthEntityMapper extends BaseMapper<UserAuthEntity> {
}
