package com.jxc.wefolio.service.miniappcode;

/**
 * 小程序码生成使用的不可变发布快照，禁止混入草稿或分享成员资料。
 *
 * @param ownerType 归属类型
 * @param ownerId 归属用户或团队主键
 * @param portfolioId 作品集主键
 * @param publishedRevision 发布版本标识
 * @param shareCode 公开分享码
 * @param uniqueCode 所属用户或团队存储目录
 * @param displayName 所属用户姓名或团队名称
 * @param subtitle 个人职业与城市，或团队城市
 * @param shareTitle 已发布分享标题
 * @param avatarUrl 所属用户或团队资料头像地址
 */
public record PortfolioMiniappCodeSnapshot(
        String ownerType, Long ownerId, Long portfolioId, String publishedRevision,
        String shareCode, String uniqueCode, String displayName, String subtitle,
        String shareTitle, String avatarUrl
) {
}
