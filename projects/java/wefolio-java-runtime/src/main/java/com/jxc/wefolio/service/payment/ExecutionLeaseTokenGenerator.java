package com.jxc.wefolio.service.payment;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * 单次任务执行租约令牌生成器。
 */
@Component
public class ExecutionLeaseTokenGenerator {

    /** 生成 32 位大写十六进制唯一令牌。 */
    public String generate() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}
