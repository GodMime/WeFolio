package com.jxc.wefolio.service.payment;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Set;
import org.xml.sax.InputSource;

/** 微信虚拟支付通知 XML 安全解析器。 */
@Component
public class WechatVirtualPaymentNotificationCodec {

    /** 允许的三类通知。 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "xpay_coin_pay_notify",
            "xpay_refund_notify",
            "xpay_subscribe_ios_refund_query_notify"
    );

    /** 解析通知业务字段。 */
    public WechatVirtualPaymentNotification parse(String xml) {
        Document document = parseDocument(xml);
        String type = firstText(document, "Event", "event", "event_type", "type");
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的微信虚拟支付通知类型");
        }
        String orderNo = firstText(document,
                "out_trade_no", "OutTradeNo", "outTradeNo", "order_id", "OrderId");
        if (!"xpay_subscribe_ios_refund_query_notify".equals(type)
                && (orderNo == null || orderNo.isBlank())) {
            throw new IllegalArgumentException("微信虚拟支付通知缺少订单号");
        }
        return new WechatVirtualPaymentNotification(type, normalize(orderNo));
    }

    /** 从外层安全模式 XML 提取 Encrypt。 */
    public String extractEncrypted(String xml) {
        return firstText(parseDocument(xml), "Encrypt");
    }

    /** 使用禁用 DTD 和外部实体的解析器。 */
    private Document parseDocument(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("微信虚拟支付通知为空");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("微信虚拟支付通知 XML 无效", exception);
        }
    }

    /** 读取第一个非空候选节点。 */
    private String firstText(Document document, String... names) {
        Element root = document.getDocumentElement();
        for (String name : names) {
            NodeList nodes = root.getElementsByTagName(name);
            if (nodes.getLength() > 0) {
                String value = normalize(nodes.item(0).getTextContent());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 去除空白并把空文本归一为空。 */
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
