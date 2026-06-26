package com.jxc.wefolio.common;

import com.jxc.wefolio.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 唯一码生成器 — 为个人（WF）和团队（TM）统一生成易读、无碰撞的唯一码。
 *
 * <p>字符集排除 I / O / 0 / 1，避免视觉混淆；采用批量预生成 + 单次 IN 查询的碰撞检测策略。</p>
 */
@Component
public class UniqueCodeGenerator {

    /** 个人唯一码前缀 */
    public static final String USER_PREFIX = "WF";

    /** 团队唯一码前缀 */
    public static final String TEAM_PREFIX = "TM";

    /** 唯一码随机字符集 — 排除 I / O / 0 / 1，共 30 个字符 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 唯一码随机部分长度 */
    private static final int RANDOM_LENGTH = 8;

    /** 每次批量生成的候选数量 */
    private static final int CANDIDATE_COUNT = 3;

    /** 密码学安全随机数生成器 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成唯一码。
     *
     * <p>一次生成 {@link #CANDIDATE_COUNT} 个候选码，通过调用方提供的 {@code usedChecker}
     * 批量查询数据库中已存在的码，返回第一个未使用的候选码。</p>
     *
     * @param prefix      前缀（例：WF 表示个人，TM 表示团队）
     * @param usedChecker 候选码碰撞检测函数 — 传入候选集合，返回其中已在库的集合
     * @return 未使用的唯一码
     * @throws BusinessException 所有候选码均已被占用时抛出
     */
    public String generate(String prefix, Function<Set<String>, Set<String>> usedChecker) {
        Set<String> candidates = new LinkedHashSet<>();
        for (int i = 0; i < CANDIDATE_COUNT; i++) {
            candidates.add(prefix + randomCode());
        }
        Set<String> used = usedChecker.apply(candidates);
        for (String code : candidates) {
            if (used == null || !used.contains(code)) {
                return code;
            }
        }
        throw new BusinessException(prefix + "唯一码生成失败，请重试");
    }

    /**
     * 生成随机码部分。
     *
     * @return 随机码字符串
     */
    private String randomCode() {
        StringBuilder builder = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
