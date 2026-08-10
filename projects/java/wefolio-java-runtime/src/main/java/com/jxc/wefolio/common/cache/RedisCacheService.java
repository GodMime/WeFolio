package com.jxc.wefolio.common.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.redis.RedisKeyNamespace;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.CacheMessage;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** 使用可读 JSON 字符串保存业务缓存的 Redis 实现。 */
@Service
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisCacheService implements CacheService {

    /** JSON 信封内的稳定类型字段。 */
    private static final String ENVELOPE_TYPE_FIELD = "type";

    /** JSON 信封内的业务值字段。 */
    private static final String ENVELOPE_VALUE_FIELD = "value";

    /** 所有 Set 实现统一使用的稳定类型标识。 */
    private static final String SET_TYPE_NAME = "java.util.Set";

    /** Redisson 客户端。 */
    private final RedissonClient redissonClient;

    /** Redis 物理 key 命名空间。 */
    private final RedisKeyNamespace keyNamespace;

    /**
     * 创建生产 Redis 缓存服务。
     *
     * @param redissonClient Redisson 客户端
     * @param keyNamespace Redis 物理 key 命名空间
     */
    public RedisCacheService(RedissonClient redissonClient, RedisKeyNamespace keyNamespace) {
        this.redissonClient = redissonClient;
        this.keyNamespace = keyNamespace;
    }

    /**
     * 按显式目标类型读取 JSON 信封，不启用 Fastjson2 AutoType。
     */
    @Override
    public <T> Optional<T> get(String key, Class<T> valueType) {
        validateKey(key);
        if (valueType == null) {
            throw new BusinessException(CacheMessage.VALUE_TYPE_REQUIRED_MESSAGE);
        }
        String json = bucket(key).get();
        if (json == null) {
            return Optional.empty();
        }
        try {
            JSONObject envelope = JSON.parseObject(json);
            String storedType = envelope.getString(ENVELOPE_TYPE_FIELD);
            if (!typeName(valueType).equals(storedType)) {
                return Optional.empty();
            }
            Object rawValue = envelope.get(ENVELOPE_VALUE_FIELD);
            T value = JSON.parseObject(JSON.toJSONString(rawValue), valueType);
            return Optional.ofNullable(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(CacheMessage.DATA_FORMAT_INVALID_MESSAGE, exception);
        }
    }

    /**
     * 将值和稳定类型标识写入可读 JSON 信封，并在 Redis 侧设置 TTL。
     */
    @Override
    public void put(String key, Object value, Duration ttl) {
        validateKey(key);
        if (value == null) {
            throw new BusinessException(CacheMessage.VALUE_REQUIRED_MESSAGE);
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new BusinessException(CacheMessage.TTL_POSITIVE_MESSAGE);
        }
        try {
            ttl.toNanos();
        } catch (ArithmeticException exception) {
            throw new BusinessException(CacheMessage.TTL_OUT_OF_RANGE_MESSAGE);
        }
        JSONObject envelope = new JSONObject();
        envelope.put(ENVELOPE_TYPE_FIELD, typeName(value.getClass()));
        envelope.put(ENVELOPE_VALUE_FIELD, value);
        bucket(key).set(JSON.toJSONString(envelope), ttl);
    }

    /** 删除 Redis 中的物理缓存 key。 */
    @Override
    public void evict(String key) {
        validateKey(key);
        bucket(key).delete();
    }

    /** 获取固定使用字符串编解码的 Redis bucket。 */
    private RBucket<String> bucket(String logicalKey) {
        return redissonClient.getBucket(keyNamespace.physicalKey(logicalKey), StringCodec.INSTANCE);
    }

    /** 将集合实现类收敛为稳定的 Set 类型标识，其它类型使用全限定类名。 */
    private String typeName(Class<?> valueType) {
        if (Set.class.isAssignableFrom(valueType)) {
            return SET_TYPE_NAME;
        }
        return valueType.getName();
    }

    /** 校验业务缓存 key。 */
    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(CacheMessage.KEY_REQUIRED_MESSAGE);
        }
    }
}
