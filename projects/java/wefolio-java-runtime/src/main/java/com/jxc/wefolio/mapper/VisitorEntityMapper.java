package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.VisitorEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * 全局访客身份表 Mapper。
 */
@Mapper
public interface VisitorEntityMapper extends BaseMapper<VisitorEntity> {

    /**
     * 尝试插入全局访客，唯一键冲突时返回 0。
     *
     * @param visitor 待插入访客
     * @return 1 表示插入成功，0 表示唯一键冲突被忽略
     */
    @Insert("""
            INSERT IGNORE INTO wf_visitor (
              openid, unionid, visitor_key, last_seen_at
            ) VALUES (
              #{openid}, #{unionid}, #{visitorKey}, #{lastSeenAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(VisitorEntity visitor);
}
