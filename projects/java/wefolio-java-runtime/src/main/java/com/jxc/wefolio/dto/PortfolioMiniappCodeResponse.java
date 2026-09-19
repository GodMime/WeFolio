package com.jxc.wefolio.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 手机端名片绘制资源；服务端仅持久化官方原码，不生成完整名片。 */
@Getter
@AllArgsConstructor
public class PortfolioMiniappCodeResponse {
    /** 官方原码持久地址。 */
    private final String codeUrl;
    /** 所属个人或团队资料头像地址；空字符串时客户端按所属类型使用本地默认头像。 */
    private final String avatarUrl;
    /** 发布资料、头像地址和布局的内容版本，用于设备端缓存。 */
    private final String contentVersion;
    /** 所属类型，参见 PortfolioOwnerTypeDict。 */
    private final String ownerType;
    /** 所属个人姓名或团队名称。 */
    private final String displayName;
    /** 个人职业与城市，或团队城市。 */
    private final String subtitle;
    /** 正式发布的分享标题。 */
    private final String shareTitle;
    /** 完整名片宽度，单位为像素。 */
    private final int width;
    /** 完整名片高度，单位为像素。 */
    private final int height;
    /** 官方原码边长，单位为像素。 */
    private final int codeSize;
    /** 中心圆头像直径，单位为像素。 */
    private final int avatarSize;
}
