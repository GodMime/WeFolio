package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.WorkTagEntity;
import org.apache.ibatis.annotations.Mapper;


/**
 * 作品标签关联表 Mapper
 */
@Mapper
public interface WorkTagEntityMapper extends BaseMapper<WorkTagEntity> {
}
