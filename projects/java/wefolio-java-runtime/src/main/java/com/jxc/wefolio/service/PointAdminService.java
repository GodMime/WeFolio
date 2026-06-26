package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.AdminPointProperties;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 后台积分服务 — 处理后台密钥校验、用户唯一码定位和人工加分委托。
 */
@Service
@RequiredArgsConstructor
public class PointAdminService {

    /** 积分服务 */
    private final PointService pointService;

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 后台积分配置 */
    private final AdminPointProperties adminPointProperties;

    /**
     * 后台人工加分。
     *
     * @param secret 请求头中的后台积分密钥
     * @param request 人工加分请求
     * @return 积分变动结果
     */
    public PointMutationResponse grantPoints(String secret, AdminPointGrantRequest request) {
        validateSecret(secret);
        if (request == null) {
            throw new BusinessException("加分内容不能为空");
        }
        String uniqueCode = normalizeRequiredString(request.getUniqueCode(), "用户唯一码不能为空");
        UserEntity user = userEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserEntity.class)
                        .eq(UserEntity::getUniqueCode, uniqueCode)
                        .eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .last("LIMIT 1")
        );
        if (user == null) {
            throw new BusinessException("用户不存在或已停用");
        }
        return pointService.grantPoints(
                user.getId(),
                request.getPoints(),
                request.getIdempotencyKey(),
                request.getRemark()
        );
    }

    /**
     * 校验后台积分密钥。
     *
     * @param secret 请求密钥
     */
    private void validateSecret(String secret) {
        String configuredSecret = adminPointProperties.getSecret();
        if (configuredSecret == null || configuredSecret.isBlank()
                || secret == null || !configuredSecret.equals(secret)) {
            throw new BusinessException("后台积分密钥无效");
        }
    }

    /**
     * 标准化必填字符串。
     *
     * @param value 原始值
     * @param message 异常文案
     * @return 字符串
     */
    private String normalizeRequiredString(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.strip();
    }
}
