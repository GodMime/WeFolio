package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.service.portfolioai.PortfolioAiApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 高级作品集 AI HTTP 适配，原文解析和全部业务分支由应用服务处理。 */
@RestController
@RequestMapping("/api/mine/portfolio-ai")
@MaintainerAccess
@RequiredArgsConstructor
public class PortfolioAiController {
    /** AI 用例入口。 */
    private final PortfolioAiApplicationService service;
    /** 获取当前能力与本人活动任务。 */
    @GetMapping("/capabilities")
    public Response<Map<String, Object>> capabilities() { return Response.success(service.capabilities()); }
    /** 下发任务，保持原始 JSON 供独立严格解析。 */
    @PostMapping("/tasks")
    public Response<Map<String, Object>> submit(@RequestBody String request) { return Response.success(service.submit(request)); }
    /** 查询动态复验后的候选或安全任务状态。 */
    @GetMapping("/tasks/{id}")
    public Response<Map<String, Object>> query(@PathVariable Long id,
            @RequestParam(required = false) String clientAdvancedVersion) { return Response.success(service.query(id, clientAdvancedVersion)); }
    /** 保留已有 Java 调用入口，HTTP 路由使用包含可选声明的适配方法。 */
    public Response<Map<String, Object>> query(Long id) { return Response.success(service.query(id)); }
    /** 取消当前活动任务。 */
    @PostMapping("/tasks/{id}/cancel")
    public Response<Map<String, Object>> cancel(@PathVariable Long id) { return Response.success(service.cancel(id)); }
}
