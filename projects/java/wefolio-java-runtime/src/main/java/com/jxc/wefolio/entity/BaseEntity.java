package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;


/**
 * 通用实体基类 — 所有表共有的基础字段
 */
@Data
public abstract class BaseEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除：0 未删除，已删除时记录本行主键 ID */
    @TableLogic(value = "0", delval = "id")
    private Long deleted;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
