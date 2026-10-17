package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import static com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceProtocol.*;

/** 采用客户端识别的参考媒体信息，只校验协议和产品上限，不读取或解码文件内容。 */
public final class PortfolioAiReferenceMediaInfo {
    /** 客户端识别值只用于确认，不扩展既有持久 JSON 合同。 */
    private static final String CODEC="codec", VIDEO_MIME="video/mp4";
    private static final Set<String> FIELDS=Set.of(MIME,CODEC,SIZE,WIDTH,HEIGHT,DURATION);
    /** 微信可能返回编码名称或 MP4 标识；未知编码用空字符串表达，不省略协议字段。 */
    private static final Set<String> VIDEO_CODECS=Set.of("h264","h.264","avc1","h265","h.265","hevc","hvc1","hev1");

    /** 纯协议工具无需实例或任何远端依赖。 */
    private PortfolioAiReferenceMediaInfo() { }

    /** 返回可签发回执的字段；HEAD 大小只用于对象身份一致性，不替代内容识别。 */
    public static ObjectNode facts(JsonNode mediaInfo,String declaredMime,long objectSize) {
        boolean video=VIDEO_MIME.equals(declaredMime);
        if(!EXTENSIONS.containsKey(declaredMime) || objectSize<=0 || objectSize>(video?VIDEO_BYTES:IMAGE_BYTES)) throw invalid();
        ObjectNode facts=NODES.objectNode().put(MIME,declaredMime).put(MEDIA,video?VIDEO:IMAGE).put(SIZE,objectSize);
        if(mediaInfo==null || !mediaInfo.isObject() || mediaInfo.size()!=FIELDS.size()) throw invalid();
        mediaInfo.fieldNames().forEachRemaining(field->{if(!FIELDS.contains(field)) throw invalid();});
        if(!declaredMime.equals(text(mediaInfo,MIME)) || !positiveLong(mediaInfo.path(SIZE))
                || mediaInfo.path(SIZE).asLong()!=objectSize || !positiveInt(mediaInfo.path(WIDTH))
                || !positiveInt(mediaInfo.path(HEIGHT))) throw invalid();
        String codec=text(mediaInfo,CODEC).trim().toLowerCase(Locale.ROOT);
        if(video && !codec.isEmpty() && !VIDEO_CODECS.contains(codec)) throw invalid();
        int width=mediaInfo.path(WIDTH).asInt(),height=mediaInfo.path(HEIGHT).asInt();
        if(!video && Math.max(width,height)>MAX_EDGE) throw invalid();
        JsonNode duration=mediaInfo.path(DURATION);
        if(video) {
            if(!positiveLong(duration) || duration.asLong()>MAX_SECONDS*1000L) throw invalid();
            facts.put(DURATION,duration.asLong());
        } else {
            if(!duration.isNull()) throw invalid();
            facts.putNull(DURATION);
        }
        return facts.put(WIDTH,width).put(HEIGHT,height);
    }

    /** 字节数和毫秒时长仅接受可无损表示的正整数。 */
    private static boolean positiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong()>0;
    }

    /** 尺寸保持既有整数协议，拒绝小数、字符串及溢出值。 */
    private static boolean positiveInt(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>0;
    }
}
