package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.jxc.wefolio.dto.MineProfileResponse;
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

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /**
     * 获取基础信息页资料
     *
     * @param userId 当前登录用户 ID
     * @return 基础信息响应
     */
    public MineProfileResponse getProfile(Long userId) {
        UserEntity user = requireActiveUser(userId);
        return buildResponse(user);
    }

    /**
     * 保存基础信息页资料
     *
     * @param userId 当前登录用户 ID
     * @param request 保存请求
     * @return 保存后的基础信息响应
     */
    public MineProfileResponse updateProfile(Long userId, MineProfileUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("资料内容不能为空");
        }

        UserEntity user = requireActiveUser(userId);
        applyStringField(request.getNickname(), NICKNAME_MAX_LENGTH, "姓名 / 艺名", user::setNickname);
        applyStringField(request.getAvatarUrl(), AVATAR_URL_MAX_LENGTH, "头像地址", user::setAvatarUrl);
        applyStringField(request.getProfession(), PROFESSION_MAX_LENGTH, "职业身份", user::setProfession);
        applyStringField(request.getCity(), CITY_MAX_LENGTH, "服务城市", user::setCity);
        applyStringField(request.getIntro(), INTRO_MAX_LENGTH, "个人简介", user::setIntro);
        if (request.getTags() != null) {
            user.setProfileTags(JSON.toJSONString(normalizeTags(request.getTags())));
        }
        user.setUpdatedAt(LocalDateTime.now());

        int updated = userEntityMapper.updateById(user);
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
     */
    private void applyStringField(String value, int maxLength, String fieldName, Consumer<String> setter) {
        if (value == null) {
            return;
        }
        setter.accept(trimAndCheckLength(value, maxLength, fieldName));
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
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
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
    private List<String> parseTags(String profileTags) {
        if (profileTags == null || profileTags.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(profileTags, String.class);
        } catch (JSONException e) {
            log.warn("解析资料标签 JSON 失败 profileTags={}", profileTags, e);
            return Collections.emptyList();
        }
    }

    /**
     * 归一化并校验标签
     *
     * @param tags 原始标签列表
     * @return 已去空格且保序的标签列表
     */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = defaultString(tag).trim();
            if (value.isBlank()) {
                throw new BusinessException("标签不能为空");
            }
            if (value.length() > TAG_MAX_LENGTH) {
                throw new BusinessException("单个标签不能超过 10 个字");
            }
            if (!seen.add(value)) {
                throw new BusinessException("标签不能重复");
            }
            normalized.add(value);
        }
        if (normalized.size() > TAG_MAX_COUNT) {
            throw new BusinessException("标签最多保留 10 个");
        }
        return normalized;
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
        String trimmed = defaultString(value).trim();
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
