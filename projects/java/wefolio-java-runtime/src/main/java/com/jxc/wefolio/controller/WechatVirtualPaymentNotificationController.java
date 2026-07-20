package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentCallbackService;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentCallbackService.CallbackResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 微信虚拟支付服务器回调入口。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payment/wechat/virtual-payment/notify")
public class WechatVirtualPaymentNotificationController {

    /** 微信虚拟支付回调应用服务 */
    private final WechatVirtualPaymentCallbackService callbackService;

    /** 微信回调地址校验，同时支持明文和安全模式。 */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "msg_signature", required = false) String messageSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echo
    ) {
        return toResponse(callbackService.verify(
                signature, messageSignature, timestamp, nonce, echo));
    }

    /** 验签、必要时解密，再分发虚拟支付通知。 */
    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
            MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> notify(
            @RequestBody String body,
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "msg_signature", required = false) String messageSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce
    ) {
        return toResponse(callbackService.notify(
                body, signature, messageSignature, timestamp, nonce));
    }

    /** 将应用服务结果转换为 HTTP 响应。 */
    private ResponseEntity<String> toResponse(CallbackResult result) {
        if (result.body() == null) {
            return ResponseEntity.status(result.statusCode()).build();
        }
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }
}
