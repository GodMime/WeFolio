package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 我的作品编辑请求。
 */
@Data
public class MineWorkUpdateRequest {

    /** 作品标题，最长 30 字 */
    private String title;

    /** 作品说明，最长 1000 字 */
    private String description;

    /** 视频封面截帧时间点，单位毫秒，仅视频作品可提交 */
    private Long coverFrameTimeMs;

    /** 小程序直传 COS 后得到的封面上传任务 ID，仅视频作品可提交 */
    private Long coverTaskId;

    /** 视频像素宽度，仅视频封面截帧时用于计算封面输出尺寸 */
    private Integer width;

    /** 视频像素高度，仅视频封面截帧时用于计算封面输出尺寸 */
    private Integer height;
}
