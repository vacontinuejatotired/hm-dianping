package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.AiService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;


/**
 * <p>
 * 聊天控制器 — AI 对话，支持普通 JSON 和 SSE 流式双模
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@Tag(name = "聊天模块", description = "聊天功能接口")
public class ChatController {

    /** SSE 超时时间：30 分钟（AI 长思考场景） */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    @Resource
    private AiService aiService;

    /**
     * 发送聊天消息 — 双模端点
     * <p>
     * 根据 {@code Accept} 请求头自动切换响应格式：
     * <ul>
     *   <li>{@code Accept: text/event-stream} → SSE 流式响应</li>
     *   <li>其他 / 无 → 普通 JSON 响应</li>
     * </ul>
     */
    @PostMapping("/string/send")
    @Operation(summary = "发送聊天消息（双模）", description =
            "JSON 模式返回 Result 信封；SSE 模式（Accept: text/event-stream）逐段推送 AI 回复 + [DONE] 标记")
    public Object chat(
            @Parameter(description = "聊天内容") @RequestParam String content,
            @Parameter(description = "客户端期望的响应格式") @RequestHeader(value = "Accept", required = false, defaultValue = "") String accept) {

        // SSE 模式
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            log.info("SSE 模式：content={}", content);
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

            // 注册回调以便日志追踪
            emitter.onCompletion(() -> log.info("SSE 流完成, content={}", content));
            emitter.onTimeout(() -> log.warn("SSE 流超时, content={}", content));
            emitter.onError(ex -> log.error("SSE 流异常, content={}", content, ex));

            // 委托 AiService 异步推送
            aiService.chatWithToolcall(content, emitter);
            return emitter;
        }
        String result = "";
            log.info("JSON 模式：content={}，accept={}", content, accept);
            result = aiService.chatReturnStringResult(content);
        
        return Result.ok(result);
    }

    @PostMapping("/flux/send")
    @Operation(summary = "预留 - Flux 模式", hidden = true)
    public Result postMethodName(@RequestBody String entity) {
        // TODO: process POST request
        return Result.ok(entity);
    }

}