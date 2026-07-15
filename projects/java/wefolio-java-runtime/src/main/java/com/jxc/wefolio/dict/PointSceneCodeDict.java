package com.jxc.wefolio.dict;

import java.util.Map;
import java.util.Optional;

/**
 * 积分场景编码字典 — 对应积分规则、流水和前端筛选条件。
 */
public class PointSceneCodeDict {

    /** 上传图片作品 */
    public static final DictValue UPLOAD_IMAGE = new DictValue("UPLOAD_IMAGE", "上传图片作品");

    /** 上传视频作品 */
    public static final DictValue UPLOAD_VIDEO = new DictValue("UPLOAD_VIDEO", "上传视频作品");

    /** 新建团队 */
    public static final DictValue CREATE_TEAM = new DictValue("CREATE_TEAM", "新建团队");

    /** 维护标准作品集 */
    public static final DictValue MAINTAIN_STANDARD_PORTFOLIO =
            new DictValue("MAINTAIN_STANDARD_PORTFOLIO", "维护标准作品集");

    /** 维护高级作品集 */
    public static final DictValue MAINTAIN_ADVANCED_PORTFOLIO =
            new DictValue("MAINTAIN_ADVANCED_PORTFOLIO", "维护高级作品集");

    /** 作品存储月费 */
    public static final DictValue MONTHLY_WORK_STORAGE =
            new DictValue("MONTHLY_WORK_STORAGE", "作品存储月费");

    /** 访问个人作品集 */
    public static final DictValue VISIT_PERSONAL_PORTFOLIO =
            new DictValue("VISIT_PERSONAL_PORTFOLIO", "访客访问个人作品集");

    /** 查看作品集图片 */
    public static final DictValue VIEW_PORTFOLIO_IMAGES =
            new DictValue("VIEW_PORTFOLIO_IMAGES", "访客查看作品集图片");

    /** 查看作品集视频 */
    public static final DictValue VIEW_PORTFOLIO_VIDEO =
            new DictValue("VIEW_PORTFOLIO_VIDEO", "访客查看作品集视频");

    /** 后台人工加分 */
    public static final DictValue MANUAL_ADMIN_GRANT =
            new DictValue("MANUAL_ADMIN_GRANT", "后台人工加分");

    /** 新用户注册赠送 */
    public static final DictValue NEW_USER_REGISTRATION_GIFT =
            new DictValue("NEW_USER_REGISTRATION_GIFT", "新用户注册赠送");

    /** 推荐用户注册赠送 */
    public static final DictValue REFERRAL_USER_GIFT =
            new DictValue("REFERRAL_USER_GIFT", "推荐用户注册赠送");

    /** 场景编码索引 */
    private static final Map<String, DictValue> CODE_MAP = Map.ofEntries(
            Map.entry(UPLOAD_IMAGE.getCode(), UPLOAD_IMAGE),
            Map.entry(UPLOAD_VIDEO.getCode(), UPLOAD_VIDEO),
            Map.entry(CREATE_TEAM.getCode(), CREATE_TEAM),
            Map.entry(MAINTAIN_STANDARD_PORTFOLIO.getCode(), MAINTAIN_STANDARD_PORTFOLIO),
            Map.entry(MAINTAIN_ADVANCED_PORTFOLIO.getCode(), MAINTAIN_ADVANCED_PORTFOLIO),
            Map.entry(MONTHLY_WORK_STORAGE.getCode(), MONTHLY_WORK_STORAGE),
            Map.entry(VISIT_PERSONAL_PORTFOLIO.getCode(), VISIT_PERSONAL_PORTFOLIO),
            Map.entry(VIEW_PORTFOLIO_IMAGES.getCode(), VIEW_PORTFOLIO_IMAGES),
            Map.entry(VIEW_PORTFOLIO_VIDEO.getCode(), VIEW_PORTFOLIO_VIDEO),
            Map.entry(MANUAL_ADMIN_GRANT.getCode(), MANUAL_ADMIN_GRANT),
            Map.entry(NEW_USER_REGISTRATION_GIFT.getCode(), NEW_USER_REGISTRATION_GIFT),
            Map.entry(REFERRAL_USER_GIFT.getCode(), REFERRAL_USER_GIFT)
    );

    private PointSceneCodeDict() {
    }

    /**
     * 根据编码获取字典值。
     *
     * @param code 场景编码
     * @return 字典值
     */
    public static Optional<DictValue> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CODE_MAP.get(code));
    }

    /**
     * 字典值。
     *
     * @param code 编码
     * @param displayName 中文展示名称
     */
    public record DictValue(String code, String displayName) {

        public String getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
