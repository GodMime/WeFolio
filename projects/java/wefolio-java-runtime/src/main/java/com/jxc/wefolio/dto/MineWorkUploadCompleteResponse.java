package com.jxc.wefolio.dto;

import com.jxc.wefolio.entity.WorkEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作品上传完成确认响应。
 */
@Data
public class MineWorkUploadCompleteResponse {

    /** 单个任务确认结果 */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个任务确认结果。
     */
    @Data
    public static class Item {

        /** 上传任务 ID */
        private Long taskId;

        /** 是否确认成功 */
        private boolean success;

        /** 确认后作品 ID */
        private Long workId;

        /** 作品标题 */
        private String title;

        /** 媒体类型：IMAGE / VIDEO */
        private String mediaType;

        /** 结果消息 */
        private String message;

        /**
         * 构造成功结果。
         *
         * @param taskId 上传任务 ID
         * @param work 作品实体
         * @param message 结果消息
         * @return 成功结果
         */
        public static Item success(Long taskId, WorkEntity work, String message) {
            Item item = new Item();
            item.setTaskId(taskId);
            item.setSuccess(true);
            if (work != null) {
                item.setWorkId(work.getId());
                item.setTitle(work.getTitle());
                item.setMediaType(work.getMediaType());
            }
            item.setMessage(message);
            return item;
        }

        /**
         * 构造失败结果。
         *
         * @param taskId 上传任务 ID
         * @param message 失败消息
         * @return 失败结果
         */
        public static Item failure(Long taskId, String message) {
            Item item = new Item();
            item.setTaskId(taskId);
            item.setSuccess(false);
            item.setMessage(message);
            return item;
        }
    }
}
