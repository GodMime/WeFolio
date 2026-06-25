package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 账号注销服务 — 负责停用用户账号并清理相关登录缓存。
 */
@Service
@RequiredArgsConstructor
public class AccountCancellationService {

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 登录令牌认证服务 */
    private final AuthTokenService authTokenService;

    /**
     * 注销当前登录用户账号。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelCurrentUser() {
        disableUser(AuthContextHolder.requireUserId());
    }

    /**
     * 停用指定用户并清理其登录缓存。管理端停用用户时也应复用此方法。
     *
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void disableUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }

        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null) {
            authTokenService.evictUser(userId);
            throw new BusinessException("用户不存在");
        }

        if (!UserStatusDict.DISABLED.getCode().equals(user.getStatus())) {
            user.setStatus(UserStatusDict.DISABLED.getCode());
            user.setUpdatedAt(LocalDateTime.now());
            int updated = userEntityMapper.updateById(user);
            if (updated <= 0) {
                throw new BusinessException("账号注销失败，请重试");
            }
        }
        authTokenService.evictUser(userId);
    }
}
