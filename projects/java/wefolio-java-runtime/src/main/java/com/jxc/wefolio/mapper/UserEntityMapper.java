package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;


/**
 * 用户表 Mapper
 */
@Mapper
public interface UserEntityMapper extends BaseMapper<UserEntity> {
}
