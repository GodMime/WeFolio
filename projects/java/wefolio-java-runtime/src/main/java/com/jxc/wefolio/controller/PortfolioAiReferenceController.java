package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 参考附件 HTTP 适配，权限、签名、探测和清理由应用服务负责。 */
@RestController
@MaintainerAccess
@RequiredArgsConstructor
@RequestMapping("/api/mine/portfolio-ai/reference-attachments")
public class PortfolioAiReferenceController {
    /** 参考附件用例入口。 */
    private final PortfolioAiReferenceService service;
    /** 申请精确对象私有上传票据。 */
    @PostMapping("/upload-tickets")
    public Response<Map<String,Object>> uploadTickets(@RequestBody String request) { return Response.success(service.uploadTicket(request)); }
    /** 确认真实文件并返回可信回执。 */
    @PostMapping("/complete")
    public Response<Map<String,Object>> complete(@RequestBody String request) { return Response.success(service.complete(request)); }
    /** 当前授权下签发短期预览地址。 */
    @PostMapping("/preview-ticket")
    public Response<Map<String,Object>> preview(@RequestBody String request) { return Response.success(service.previewTicket(request)); }
}
