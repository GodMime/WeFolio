package com.jxc.wefolio.job.service;

/** COS HEAD 确认的源身份，不包含原文件字节或签名链接。 */
public record VideoFrameSource(String objectKey, String sourceVersion, String etag, long size) { }
