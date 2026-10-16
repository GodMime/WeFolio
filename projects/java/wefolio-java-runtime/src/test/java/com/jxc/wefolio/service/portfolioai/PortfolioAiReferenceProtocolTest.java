package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceProtocol.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 持久 JSON 损坏必须拒绝，避免清理误判为无引用。 */
class PortfolioAiReferenceProtocolTest {
    /** 小数或字符串版本不属于协议版本一。 */
    @Test void rejectsCoercedVersionAndWatermark() {
        for(String raw:new String[]{"{\"version\":\"1\",\"items\":[]}","{\"version\":1.2,\"items\":[]}","{\"version\":1,\"items\":[],\"nextReferenceNo\":2.5}"})
            assertThatThrownBy(()->stored(raw)).isInstanceOf(BusinessException.class);
    }
    /** 已存事实缺少字段、尺寸溢出或高水位落后均不能成为保护清单。 */
    @Test void rejectsCorruptedFactsAndWatermark() {
        ObjectNode root=valid();assertThat(stored(root.toString())).isEqualTo(root);
        root.put(NEXT,1);assertThatThrownBy(()->stored(root.toString())).isInstanceOf(BusinessException.class);
        root.put(NEXT,2);((ObjectNode)root.path(ITEMS).get(0)).put(WIDTH,4_294_967_297L);
        assertThatThrownBy(()->stored(root.toString())).isInstanceOf(BusinessException.class);
    }
    /** 业务条目禁止客户端提供 objectKey 或真实时长。 */
    @Test void rejectsClientFileFacts() {
        ObjectNode root=valid();
        assertThatThrownBy(()->business(root.path(ITEMS))).isInstanceOf(BusinessException.class);
    }
    /** 创建完整合法的图片持久快照。 */
    private ObjectNode valid() {
        ObjectNode root=NODES.objectNode().put(VERSION,1).put(NEXT,2);
        ObjectNode item=root.putArray(ITEMS).addObject().put(ID,"0123456789abcdef0123456789abcdef").put(NUMBER,"A1")
                .put(KEY,"WFTEST"+PREFIX+"0123456789abcdef0123456789abcdef.png").put(MEDIA,IMAGE).put(NAME,"参考.png")
                .put(MIME,"image/png").put(SIZE,100).put(WIDTH,10).put(HEIGHT,20).put(NOTE,"");
        item.putNull(DURATION);item.putObject(CONTENT).put(ETAG,"etag").put(MODIFIED,1_700_000_000_000L);return root;
    }
}
