package com.hmdp.service.impl;

import com.hmdp.service.AiService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
@Slf4j
public class AiServiceImpl implements AiService {

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    @Override
    public String chatReturnStringResult(String content) {
        log.info("AI 调用：{}", content);
        String result = chatClient.prompt().user(content).call().content();
        log.info("AI 回复：{}", result);
        return result;
    }

    @Override
    public void chatStream(String content, SseEmitter emitter) {
        log.info("AI 流式调用：{}", content);

        // 订阅 Spring AI 的流式 Flux<String>
        chatClient.prompt().user(content).stream().content()
                .subscribe(
                        chunk -> {
                            // 逐段推送 SSE 事件
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                log.error("SSE 发送失败，中断流", e);
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            // AI 调用异常 → 推送错误帧后结束
                            log.error("AI 流式调用异常", error);
                            try {
                                emitter.send(SseEmitter.event()
                                        .data("{\"error\":\"" + escapeJson(error.getMessage()) + "\",\"code\":5001}"));
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        () -> {
                            // AI 流正常结束
                            log.info("AI 流式调用完成");
                            try {
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
    }

    /**
     * 简易 JSON 转义，防止 error message 中的特殊字符破坏 JSON 格式
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
