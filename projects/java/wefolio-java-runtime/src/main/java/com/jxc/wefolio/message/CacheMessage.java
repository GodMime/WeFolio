package com.jxc.wefolio.message;

/** Redis 缓存参数校验与数据解析异常文案。 */
public interface CacheMessage {

    /** 缓存 key 为空提示。 */
    String KEY_REQUIRED_MESSAGE = "缓存 key 不能为空";

    /** 缓存目标值类型为空提示。 */
    String VALUE_TYPE_REQUIRED_MESSAGE = "缓存值类型不能为空";

    /** 缓存值为空提示。 */
    String VALUE_REQUIRED_MESSAGE = "缓存值不能为空";

    /** 缓存有效期非正数提示。 */
    String TTL_POSITIVE_MESSAGE = "缓存有效期必须大于 0";

    /** 缓存有效期超出 Java 时长换算范围提示。 */
    String TTL_OUT_OF_RANGE_MESSAGE = "缓存有效期超出支持范围";

    /** Redis 缓存 JSON 信封无法解析提示。 */
    String DATA_FORMAT_INVALID_MESSAGE = "Redis 缓存数据格式异常";
}
