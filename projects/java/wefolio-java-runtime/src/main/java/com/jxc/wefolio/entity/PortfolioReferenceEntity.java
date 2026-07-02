package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_portfolio_reference — 作品集当前引用表 — 记录配置中引用的作品、成员作品集等资源
 */
@Data
@TableName("wf_portfolio_reference")
public class PortfolioReferenceEntity extends BaseEntity {

    /** 当前生效作品集 ID */
    private Long portfolioId;

    /** 配置作用域：DRAFT 草稿 / PUBLISHED 正式 */
    private String configScope;

    /** 引用类型：WORK / MEMBER_PORTFOLIO / USER_PROFILE / TEAM_PROFILE / SCHEDULE_COMPONENT / QR_CODE_ASSET */
    private String referenceType;

    /** 被引用业务记录 ID，档期组件等虚拟引用可取所有者 ID */
    private Long referenceId;

    /** 组件实例键 */
    private String componentKey;

    /** Schema 内引用位置，如 components[0].items[2] */
    private String componentPath;

    /** 组件内排序 */
    private Integer sortOrder;

    /** 当前显示所需标题、封面等快照 JSON */
    private String snapshotJson;

    /** 当前引用是否仍有效：0 失效 / 1 有效 */
    private Integer isValid;

    /** 失效原因（成员退出、撤回授权、作品删除等） */
    private String invalidReason;

}
