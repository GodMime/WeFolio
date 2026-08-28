package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 作品审核任务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "work-audit")
public class WorkAuditProperties {

    /** 是否启用作品审核定时任务 */
    private boolean enabled = true;

    /** 调度表达式 */
    private String cron = "0 * * * * ?";

    /** 当前实例锁标识前缀 */
    private String lockOwnerPrefix = "local";

    /** 单条任务锁定秒数 */
    private int taskLockSeconds = 300;

    /** 每轮最多查询的视频任务数 */
    private int maxQueryVideoPerRun = 1000;

    /** 每轮最多提交的视频作品数 */
    private int maxSubmitVideoPerRun = 500;

    /** 单个视频审核任务最大提交尝试次数 */
    private int videoSubmitMaxAttempts = 3;

    /** 每轮最多审核的图片作品数 */
    private int maxAuditImagePerRun = 500;

    /** 单个图片审核任务最大尝试次数 */
    private int imageMaxAttempts = 3;

    /** 每轮最多尝试审核的动图任务数 */
    private int maxAuditAnimationPerRun = 500;

    /** 单个动图审核任务最大尝试次数 */
    private int animationMaxAttempts = 3;

    /** 单个视频审核任务最多主动查询次数 */
    private int videoQueryMaxAttempts = 120;

    /** 视频截帧间隔秒数 */
    private int videoSnapshotIntervalSeconds = 60;

    /** 单个视频最大截帧数量 */
    private int maxVideoSnapshotCount = 120;

    /** 腾讯云远端调用超时时间，单位秒 */
    private int remoteCallTimeoutSeconds = 30;

    /**
     * 设置每轮动图审核上限。
     *
     * @param maxAuditAnimationPerRun 正整数上限
     */
    public void setMaxAuditAnimationPerRun(int maxAuditAnimationPerRun) {
        if (maxAuditAnimationPerRun <= 0) {
            throw new IllegalArgumentException("每轮动图审核上限必须为正整数");
        }
        this.maxAuditAnimationPerRun = maxAuditAnimationPerRun;
    }

    /**
     * 设置图片审核最大尝试次数。
     *
     * @param imageMaxAttempts 正整数上限
     */
    public void setImageMaxAttempts(int imageMaxAttempts) {
        if (imageMaxAttempts <= 0) {
            throw new IllegalArgumentException("图片审核最大尝试次数必须为正整数");
        }
        this.imageMaxAttempts = imageMaxAttempts;
    }

    /**
     * 设置动图审核最大尝试次数。
     *
     * @param animationMaxAttempts 正整数上限
     */
    public void setAnimationMaxAttempts(int animationMaxAttempts) {
        if (animationMaxAttempts <= 0) {
            throw new IllegalArgumentException("动图审核最大尝试次数必须为正整数");
        }
        this.animationMaxAttempts = animationMaxAttempts;
    }

    /**
     * 设置视频审核提交最大尝试次数。
     *
     * @param videoSubmitMaxAttempts 正整数上限
     */
    public void setVideoSubmitMaxAttempts(int videoSubmitMaxAttempts) {
        if (videoSubmitMaxAttempts <= 0) {
            throw new IllegalArgumentException("视频审核提交最大尝试次数必须为正整数");
        }
        this.videoSubmitMaxAttempts = videoSubmitMaxAttempts;
    }
}
