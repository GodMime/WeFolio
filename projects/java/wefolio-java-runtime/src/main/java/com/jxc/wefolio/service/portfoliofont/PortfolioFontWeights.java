package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ClassPathResource;
import java.io.InputStream;

/** 追加式固定版本物理字重表；旧版本身份不随当前目录变化。 */
public final class PortfolioFontWeights {
    /** 包内跨语言固定映射。 */
    public static final String RESOURCE = "fonts/physical-weights.json";
    /** 源表损坏时不推测合成粗体，节点源校验会拒绝生成。 */
    private static final JSONObject VERSIONS = read();
    /** 工具类不允许实例化。 */
    private PortfolioFontWeights() { }
    /** 未知版本保留请求字重，不套用最新字体的物理定义。 */
    public static int physical(String id, String version, int requested) {
        JSONObject weights = VERSIONS.getJSONObject(id + ":" + version);
        return weights == null ? requested : weights.getIntValue(String.valueOf(requested), requested);
    }
    /** 读取失败不使静态初始化或旧业务失败。 */
    private static JSONObject read() {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            return JSON.parseObject(input.readAllBytes());
        } catch (Exception exception) { return new JSONObject(); }
    }
}
