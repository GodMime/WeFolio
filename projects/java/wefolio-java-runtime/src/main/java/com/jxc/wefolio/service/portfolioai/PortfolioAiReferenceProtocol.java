package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 两个 JSON 列共用的封闭参考附件协议，业务投影不包含临时凭证。 */
public final class PortfolioAiReferenceProtocol {
    /** 协议字段及固定标识。 */
    public static final String VERSION="version", ITEMS="items", NEXT="nextReferenceNo", ID="attachmentId",
            NUMBER="referenceNo", NOTE="note", TOKEN="attachmentToken", UPLOAD_TOKEN="uploadToken",
            OWNER_TYPE="ownerType", OWNER_ID="ownerId", PORTFOLIO_ID="portfolioId", KEY="objectKey",
            MEDIA="mediaType", NAME="originalFileName", MIME="mimeType", SIZE="sizeBytes", DURATION="durationMs",
            WIDTH="width", HEIGHT="height", CONTENT="contentVersion", ETAG="etag", MODIFIED="lastModifiedEpochMs",
            PURPOSE="purpose", EXPIRES="expiresAt", UPLOAD="UPLOAD", ATTACHMENT="ATTACHMENT",
            IMAGE="IMAGE", VIDEO="VIDEO", PREFIX="/protfolio/ai-reference/";
    /** 协议限制使用十进制字节。 */
    public static final int MAX_BYTES=65_536, MAX_COUNT=6, MAX_EDGE=4096, MAX_SECONDS=300;
    /** 文件大小和暂存窗口。 */
    public static final long IMAGE_BYTES=4_000_000L, VIDEO_BYTES=100_000_000L, WINDOW_MS=86_400_000L;
    /** 文件扩展名只由 MIME 白名单确定。 */
    public static final Map<String,String> EXTENSIONS=Map.of("image/jpeg","jpg","image/png","png","video/mp4","mp4");
    /** 请求条目不能带文件事实，事实只能来自可信回执或数据库。 */
    private static final Set<String> INPUT_KEYS=Set.of(ID,NUMBER,NOTE,TOKEN);
    /** 持久条目的完整字段集合。 */
    private static final Set<String> STORED_KEYS=Set.of(ID,NUMBER,KEY,MEDIA,NAME,MIME,SIZE,DURATION,WIDTH,HEIGHT,CONTENT,NOTE);
    /** 节点工厂。 */
    public static final JsonNodeFactory NODES=JsonNodeFactory.instance;
    private PortfolioAiReferenceProtocol() { }

    /** 只验证业务结构，用于幂等重放；不核验可能已过期的签名。 */
    public static ArrayNode business(JsonNode values) {
        ArrayNode result=NODES.arrayNode();
        if(values==null || values.isNull()) return result;
        if(!values.isArray() || values.size()>MAX_COUNT || values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES) throw invalid();
        Set<String> ids=new HashSet<>(), numbers=new HashSet<>();
        for(JsonNode value:values) {
            if(!value.isObject()) throw invalid();
            value.fieldNames().forEachRemaining(name->{if(!INPUT_KEYS.contains(name)) throw invalid();});
            String id=text(value,ID), number=text(value,NUMBER);
            if(!id.matches("[0-9a-f]{32}") || !number.matches("A[1-9][0-9]{0,8}") || !ids.add(id) || !numbers.add(number)) throw invalid();
            String note=value.has(NOTE)?text(value,NOTE):"";
            if(note.codePointCount(0,note.length())>200 || value.has(TOKEN) && !value.path(TOKEN).isTextual()) throw invalid();
            result.addObject().put(ID,id).put(NUMBER,number).put(NOTE,note);
        }
        return result;
    }

    /** 验证持久 JSON；损坏时必须中断引用判断，不能解释成空清单。 */
    public static ObjectNode stored(String raw) {
        if(raw==null) return NODES.objectNode().put(VERSION,1).put(NEXT,1).set(ITEMS,NODES.arrayNode());
        ObjectNode node;
        try { node=StrictAiJson.parseObject(raw,MAX_BYTES,8); }
        catch(RuntimeException exception) { throw invalid(); }
        if(!node.path(VERSION).isIntegralNumber() || !node.path(VERSION).canConvertToInt() || node.path(VERSION).asInt(-1)!=1 || !node.path(ITEMS).isArray() || node.path(ITEMS).size()>MAX_COUNT) throw invalid();
        node.fieldNames().forEachRemaining(field->{if(!Set.of(VERSION,ITEMS,NEXT).contains(field)) throw invalid();});
        ArrayNode business=NODES.arrayNode();
        int videos=0;
        for(JsonNode item:node.path(ITEMS)) {
            validateFacts(item);
            if(!item.isObject() || item.size()!=STORED_KEYS.size()) throw invalid();
            item.fieldNames().forEachRemaining(field->{if(!STORED_KEYS.contains(field)) throw invalid();});
            business.addObject().setAll(Map.of(ID,item.path(ID),NUMBER,item.path(NUMBER),NOTE,item.path(NOTE)));
            if(VIDEO.equals(item.path(MEDIA).asText())) videos++;
        }
        business(business);
        if(videos>1 || node.has(NEXT) && (!node.path(NEXT).isIntegralNumber() || !node.path(NEXT).canConvertToInt() || node.path(NEXT).asInt()<1 || node.path(NEXT).asInt()>1_000_000_000)) throw invalid();
        if(node.has(NEXT)) for(JsonNode item:business) if(Integer.parseInt(item.path(NUMBER).asText().substring(1))>=node.path(NEXT).asInt()) throw invalid();
        return node;
    }

    /** 文件事实校验同时用于服务端签名载荷与持久清单。 */
    public static void validateFacts(JsonNode item) {
        String id=text(item,ID),mime=text(item,MIME),key=text(item,KEY),name=text(item,NAME);
        if(!id.matches("[0-9a-f]{32}") || !EXTENSIONS.containsKey(mime) || !key.endsWith(PREFIX+id+"."+EXTENSIONS.get(mime))
                || name.isBlank() || name.codePointCount(0,name.length())>200 || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        boolean video="video/mp4".equals(mime);
        if(!text(item,MEDIA).equals(video?VIDEO:IMAGE) || !item.path(SIZE).isIntegralNumber() || !item.path(SIZE).canConvertToLong() || item.path(SIZE).asLong()<=0
                || item.path(SIZE).asLong()>(video?VIDEO_BYTES:IMAGE_BYTES) || !item.path(WIDTH).isIntegralNumber() || !item.path(WIDTH).canConvertToInt() || !item.path(HEIGHT).isIntegralNumber() || !item.path(HEIGHT).canConvertToInt()
                || item.path(WIDTH).asInt()<=0 || item.path(HEIGHT).asInt()<=0 || !video && Math.max(item.path(WIDTH).asInt(),item.path(HEIGHT).asInt())>MAX_EDGE) throw invalid();
        if(video && (!item.path(DURATION).isIntegralNumber() || !item.path(DURATION).canConvertToLong() || item.path(DURATION).asLong()<=0 || item.path(DURATION).asLong()>MAX_SECONDS*1000L)
                || !video && !item.path(DURATION).isNull()) throw invalid();
        JsonNode version=item.path(CONTENT);
        if(!version.isObject() || version.size()!=2 || text(version,ETAG).isBlank() || !version.path(MODIFIED).isIntegralNumber() || !version.path(MODIFIED).canConvertToLong() || version.path(MODIFIED).asLong()<=0 || version.path(MODIFIED).asLong()>Long.MAX_VALUE-WINDOW_MS) throw invalid();
    }

    /** 读取明确字符串，禁止把数字或对象隐式转换成可信文本。 */
    public static String text(JsonNode node,String key) { if(!node.path(key).isTextual()) throw invalid(); return node.path(key).textValue(); }
    /** 统一协议错误。 */
    public static BusinessException invalid() { return new BusinessException(PortfolioAiReferenceMessage.INVALID); }
}
