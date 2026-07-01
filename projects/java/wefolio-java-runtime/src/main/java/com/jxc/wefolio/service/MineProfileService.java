package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileTagDTO;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 基础信息服务 — 负责维护者个人资料读取与保存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineProfileService {

    /** 姓名或艺名最大长度 */
    private static final int NICKNAME_MAX_LENGTH = 50;

    /** 头像地址最大长度 */
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    /** 职业身份最大长度 */
    private static final int PROFESSION_MAX_LENGTH = 50;

    /** 服务城市最大长度 */
    private static final int CITY_MAX_LENGTH = 50;

    /** 个人简介最大长度 */
    private static final int INTRO_MAX_LENGTH = 500;

    /** 标签最大数量 */
    private static final int TAG_MAX_COUNT = 10;

    /** 单个标签最大长度 */
    private static final int TAG_MAX_LENGTH = 10;

    /** 默认标签颜色 */
    private static final String DEFAULT_TAG_COLOR = "#0f766e";

    /** 可选标签色板 */
    private static final Set<String> TAG_COLORS = Set.of(
            "#0f766e",
            "#2d5f9a",
            "#8a4b09",
            "#a9354f",
            "#6d5bd0",
            "#3f6f45",
            "#36516e",
            "#9a4a35",
            "#4b5563"
    );

    /** 用户表主键列 */
    private static final String COL_ID = "id";

    /** 昵称列 */
    private static final String COL_NICKNAME = "nickname";

    /** 头像列 */
    private static final String COL_AVATAR_URL = "avatar_url";

    /** 职业身份列 */
    private static final String COL_PROFESSION = "profession";

    /** 服务城市列 */
    private static final String COL_CITY = "city";

    /** 个人简介列 */
    private static final String COL_INTRO = "intro";

    /** 标签列 */
    private static final String COL_PROFILE_TAGS = "profile_tags";

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /**
     * 获取基础信息页资料
     *
     * @return 基础信息响应
     */
    public MineProfileResponse getProfile() {
        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = requireActiveUser(userId);
        return buildResponse(user);
    }

    /**
     * 保存基础信息页资料
     *
     * @param request 保存请求
     * @return 保存后的基础信息响应
     */
    public MineProfileResponse updateProfile(MineProfileUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("资料内容不能为空");
        }

        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = requireActiveUser(userId);
        UpdateWrapper<UserEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COL_ID, userId);

        applyStringField(request.getNickname(), NICKNAME_MAX_LENGTH, "姓名 / 艺名",
                user::setNickname, value -> updateWrapper.set(COL_NICKNAME, value));
        applyAvatarField(request.getAvatarUrl(), user, updateWrapper);
        applyStringField(request.getProfession(), PROFESSION_MAX_LENGTH, "职业身份",
                user::setProfession, value -> updateWrapper.set(COL_PROFESSION, value));
        applyStringField(request.getCity(), CITY_MAX_LENGTH, "服务城市",
                user::setCity, value -> updateWrapper.set(COL_CITY, value));
        applyStringField(request.getIntro(), INTRO_MAX_LENGTH, "个人简介",
                user::setIntro, value -> updateWrapper.set(COL_INTRO, value));
        if (request.getTags() != null) {
            String profileTags = JSON.toJSONString(normalizeTags(request.getTags()));
            user.setProfileTags(profileTags);
            updateWrapper.set(COL_PROFILE_TAGS, profileTags);
        }

        int updated = userEntityMapper.update(new UserEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException("资料保存失败，请重试");
        }
        return buildResponse(user);
    }

    /**
     * 请求字段存在时才覆盖实体字段；字段缺失时保留数据库原值
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @param setter 实体字段赋值方法
     * @param updateSetter 局部更新字段赋值方法
     */
    private void applyStringField(
            String value,
            int maxLength,
            String fieldName,
            Consumer<String> setter,
            Consumer<String> updateSetter
    ) {
        if (value == null) {
            return;
        }
        String normalized = trimAndCheckLength(value, maxLength, fieldName);
        setter.accept(normalized);
        updateSetter.accept(normalized);
    }

    /**
     * 处理头像字段更新，包含每月 10 次限制校验。
     * 跨月时自动重置计数。
     *
     * @param avatarUrl     新头像地址，为空时不更新
     * @param user          当前用户实体
     * @param updateWrapper 局部更新包装器
     */
    private void applyAvatarField(String avatarUrl, UserEntity user, UpdateWrapper<UserEntity> updateWrapper) {
        if (avatarUrl == null) {
            return;
        }
        String normalized = trimAndCheckLength(avatarUrl, AVATAR_URL_MAX_LENGTH, "头像地址");

        // 头像地址未变化，跳过计数
        if (normalized.equals(user.getAvatarUrl())) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int newCount = computeMonthlyAvatarUpdateCount(user, now);
        if (newCount > UserEntity.AVATAR_MONTHLY_MAX_COUNT) {
            throw new BusinessException("当月头像更新次数已达上限（" + UserEntity.AVATAR_MONTHLY_MAX_COUNT + "次），请下月再试");
        }

        user.setAvatarUrl(normalized);
        updateWrapper.set(COL_AVATAR_URL, normalized);
        user.setLastAvatarUpdatedAt(now);
        updateWrapper.set("last_avatar_updated_at", now);
        user.setAvatarUpdateCount(newCount);
        updateWrapper.set("avatar_update_count", newCount);
    }

    /**
     * 计算当月头像更新次数，跨月自动重置。
     *
     * @param user 当前用户实体
     * @param now  当前时间
     * @return 本次更新后的计数值
     */
    private int computeMonthlyAvatarUpdateCount(UserEntity user, LocalDateTime now) {
        LocalDateTime lastUpdate = user.getLastAvatarUpdatedAt();
        int currentCount = user.getAvatarUpdateCount() != null ? user.getAvatarUpdateCount() : 0;

        boolean newMonth = lastUpdate == null
                || lastUpdate.getYear() != now.getYear()
                || lastUpdate.getMonthValue() != now.getMonthValue();
        return newMonth ? 1 : currentCount + 1;
    }

    /**
     * 查询并校验当前用户状态
     *
     * @param userId 当前登录用户 ID
     * @return 活跃用户实体
     */
    private UserEntity requireActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        return user;
    }

    /**
     * 构建基础信息响应
     *
     * @param user 用户实体
     * @return 基础信息响应
     */
    private MineProfileResponse buildResponse(UserEntity user) {
        MineProfileResponse response = new MineProfileResponse();
        response.setUserId(user.getId());
        response.setUniqueCode(defaultString(user.getUniqueCode()));
        response.setDisplayName(buildDisplayName(user));
        response.setNickname(defaultString(user.getNickname()));
        response.setAvatarUrl(defaultString(user.getAvatarUrl()));
        response.setProfession(defaultString(user.getProfession()));
        response.setCity(defaultString(user.getCity()));
        response.setIntro(defaultString(user.getIntro()));
        response.setTags(parseTags(user.getProfileTags()));
        return response;
    }

    /**
     * 解析资料标签
     *
     * @param profileTags 标签 JSON
     * @return 标签列表
     */
    private List<MineProfileTagDTO> parseTags(String profileTags) {
        if (profileTags == null || profileTags.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JSONArray tags = JSON.parseArray(profileTags);
            return normalizeTags(tags);
        } catch (BusinessException | JSONException e) {
            log.warn("解析资料标签 JSON 失败 profileTags={}", profileTags, e);
            return Collections.emptyList();
        }
    }

    /**
     * 归一化并校验标签
     *
     * @param tags 原始标签列表，兼容字符串或对象
     * @return 已去空格且保序的标签列表
     */
    private List<MineProfileTagDTO> normalizeTags(List<?> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }

        List<MineProfileTagDTO> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object tag : tags) {
            if (seen.size() >= TAG_MAX_COUNT) {
                throw new BusinessException("标签最多保留 10 个");
            }
            String value = extractTagContent(tag).strip();
            if (value.isBlank()) {
                throw new BusinessException("标签不能为空");
            }
            if (value.length() > TAG_MAX_LENGTH) {
                throw new BusinessException("单个标签不能超过 10 个字");
            }
            if (!seen.add(value)) {
                throw new BusinessException("标签不能重复");
            }
            MineProfileTagDTO normalizedTag = new MineProfileTagDTO();
            normalizedTag.setContent(value);
            normalizedTag.setColor(normalizeTagColor(tag));
            normalized.add(normalizedTag);
        }
        return normalized;
    }

    /**
     * 提取标签内容，兼容旧版字符串数组。
     *
     * @param tag 原始标签项
     * @return 标签内容
     */
    private String extractTagContent(Object tag) {
        if (tag instanceof MineProfileTagDTO profileTag) {
            return defaultString(profileTag.getContent());
        }
        if (tag instanceof JSONObject jsonObject) {
            return firstPresent(jsonObject.getString("content"), jsonObject.getString("text"), jsonObject.getString("name"));
        }
        if (tag instanceof Map<?, ?> map) {
            return firstPresent(mapValue(map, "content"), mapValue(map, "text"), mapValue(map, "name"));
        }
        return defaultString(tag == null ? null : String.valueOf(tag));
    }

    /**
     * 提取并校验标签颜色。
     *
     * @param tag 原始标签项
     * @return 规范化后的标签颜色
     */
    private String normalizeTagColor(Object tag) {
        String color = DEFAULT_TAG_COLOR;
        if (tag instanceof MineProfileTagDTO profileTag) {
            color = defaultString(profileTag.getColor());
        } else if (tag instanceof JSONObject jsonObject) {
            color = defaultString(jsonObject.getString("color"));
        } else if (tag instanceof Map<?, ?> map) {
            color = mapValue(map, "color");
        }

        String normalized = defaultString(color).strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return DEFAULT_TAG_COLOR;
        }
        if (!TAG_COLORS.contains(normalized)) {
            throw new BusinessException("请选择有效的标签颜色");
        }
        return normalized;
    }

    /**
     * 从 Map 中取字符串值。
     *
     * @param map 原始 Map
     * @param key 字段名
     * @return 字符串值
     */
    private String mapValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param values 待选择字符串
     * @return 非空字符串
     */
    private String firstPresent(String... values) {
        for (String value : values) {
            if (!defaultString(value).isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 去除首尾空格并校验长度
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @return 归一化后的字符串
     */
    private String trimAndCheckLength(String value, int maxLength, String fieldName) {
        String trimmed = defaultString(value).strip();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过 " + maxLength + " 个字");
        }
        return trimmed;
    }

    /**
     * 构建展示名称
     *
     * @param user 用户实体
     * @return 页面展示名称
     */
    private String buildDisplayName(UserEntity user) {
        String nickname = defaultString(user.getNickname());
        String profession = defaultString(user.getProfession());
        if (nickname.isBlank() && profession.isBlank()) {
            return "微信用户";
        }
        if (profession.isBlank()) {
            return nickname;
        }
        if (nickname.isBlank()) {
            return profession;
        }
        return nickname + " · " + profession;
    }

    /**
     * 空值安全字符串
     *
     * @param value 原字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
