package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 历史用户 COS 目录修复配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "user-storage-folder-repair")
public class UserStorageFolderRepairProperties {

    /** 单次游标分页用户数 */
    private int batchSize = 200;

    /**
     * 设置批大小。
     *
     * @param batchSize 正整数批大小
     */
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("历史用户目录修复批大小必须为正整数");
        }
        this.batchSize = batchSize;
    }
}
