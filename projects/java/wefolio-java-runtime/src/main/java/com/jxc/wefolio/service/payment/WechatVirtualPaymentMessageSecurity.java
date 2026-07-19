package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** 微信虚拟支付消息签名校验与安全模式解密服务。 */
@Service
public class WechatVirtualPaymentMessageSecurity {

    /** 微信消息加密块大小。 */
    private static final int BLOCK_SIZE = 32;

    /** 随机前缀长度。 */
    private static final int RANDOM_PREFIX_LENGTH = 16;

    /** 消息长度字段字节数。 */
    private static final int MESSAGE_LENGTH_BYTES = 4;

    /** 回调安全配置。 */
    private final WechatVirtualPaymentProperties properties;

    /** 微信小程序配置。 */
    private final WechatMiniappProperties miniappProperties;

    /** 创建回调安全服务。 */
    public WechatVirtualPaymentMessageSecurity(
            WechatVirtualPaymentProperties properties,
            WechatMiniappProperties miniappProperties
    ) {
        this.properties = properties;
        this.miniappProperties = miniappProperties;
    }

    /** 校验明文模式或 GET 地址校验签名。 */
    public boolean verifyPlaintextSignature(String signature, String timestamp, String nonce) {
        return constantTimeEquals(signature, sha1Sorted(properties.getMessageToken(), timestamp, nonce));
    }

    /** 校验安全模式签名并解密消息。 */
    public String decryptMessage(
            String messageSignature,
            String timestamp,
            String nonce,
            String encrypted
    ) {
        String expected = sha1Sorted(properties.getMessageToken(), timestamp, nonce, encrypted);
        if (!constantTimeEquals(messageSignature, expected)) {
            throw new IllegalArgumentException("微信回调消息签名无效");
        }
        return decrypt(encrypted);
    }

    /** 解密并校验回调 AppID。 */
    private String decrypt(String encrypted) {
        try {
            byte[] key = decodeAesKey();
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(key, 16)));
            byte[] padded = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            byte[] plain = removePkcs7Padding(padded);
            if (plain.length < RANDOM_PREFIX_LENGTH + MESSAGE_LENGTH_BYTES) {
                throw new IllegalArgumentException("微信回调密文长度无效");
            }
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            buffer.position(RANDOM_PREFIX_LENGTH);
            int messageLength = buffer.getInt();
            if (messageLength < 0 || messageLength > buffer.remaining()) {
                throw new IllegalArgumentException("微信回调消息长度无效");
            }
            byte[] message = new byte[messageLength];
            buffer.get(message);
            byte[] appId = new byte[buffer.remaining()];
            buffer.get(appId);
            String decryptedAppId = new String(appId, StandardCharsets.UTF_8);
            if (!constantTimeEquals(miniappProperties.getAppId(), decryptedAppId)) {
                throw new IllegalArgumentException("微信回调 AppID 不匹配");
            }
            return new String(message, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("微信回调密文解密失败", exception);
        }
    }

    /** 解码 43 位 EncodingAESKey。 */
    private byte[] decodeAesKey() {
        String encodingKey = properties.getMessageEncodingAesKey();
        if (encodingKey == null || encodingKey.length() != 43) {
            throw new IllegalArgumentException("微信回调 EncodingAESKey 格式无效");
        }
        byte[] key = Base64.getDecoder().decode(encodingKey + "=");
        if (key.length != 32) {
            throw new IllegalArgumentException("微信回调 EncodingAESKey 长度无效");
        }
        return key;
    }

    /** 移除微信 PKCS#7 填充。 */
    private byte[] removePkcs7Padding(byte[] padded) {
        if (padded.length == 0) {
            throw new IllegalArgumentException("微信回调密文为空");
        }
        int padding = Byte.toUnsignedInt(padded[padded.length - 1]);
        if (padding < 1 || padding > BLOCK_SIZE || padding > padded.length) {
            throw new IllegalArgumentException("微信回调填充无效");
        }
        for (int index = padded.length - padding; index < padded.length; index++) {
            if (Byte.toUnsignedInt(padded[index]) != padding) {
                throw new IllegalArgumentException("微信回调填充无效");
            }
        }
        return Arrays.copyOf(padded, padded.length - padding);
    }

    /** 按微信规则排序后计算 SHA-1。 */
    private String sha1Sorted(String... values) {
        try {
            String joined = Arrays.stream(values)
                    .map(value -> value == null ? "" : value)
                    .sorted()
                    .reduce("", String::concat);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("微信回调签名计算失败", exception);
        }
    }

    /** 使用固定时间比较避免签名旁路。 */
    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
