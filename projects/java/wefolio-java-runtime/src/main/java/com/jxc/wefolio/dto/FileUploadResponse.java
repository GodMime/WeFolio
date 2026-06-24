package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 文件上传响应
 */
@Data
public class FileUploadResponse {

    /** COS 对象键 */
    private String key;

    /** 文件公开访问地址 */
    private String url;
}
