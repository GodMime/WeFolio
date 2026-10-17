package com.jxc.wefolio.job.service;

/** 供应商真实截图位置；审核填 URL，历史补帧填受控同桶对象键。 */
public record VideoFrameInput(Long timeMs, String url, String objectKey) { }
