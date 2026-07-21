package com.hmdp.service.impl;

import com.hmdp.service.AiService;
import com.hmdp.utils.UserHolder;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AiServiceImpl implements AiService {

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    private String END = "[DONE]";

    private String ASK_PERMISSION = "[请确认是否授权调用工具]";

    @Override
    public String chatReturnStringResult(String content) {
        log.info("AI 调用：{}", content);
        String result = chatClient.prompt().user(content).call().content();
        log.info("AI 回复：{}", result);
        return result;
    }

    // @Override
    // public void chatStream(String content, SseEmitter emitter) {
    //     log.info("AI 流式调用：{}", content);
    //     StreamResponseSpec stream = chatClient.prompt().user(content).stream();
        
    //     // 订阅 Spring AI 的流式 Flux<String>
    //     stream.chatClientResponse()
    //             .subscribe(
    //                     response -> {
    //                         // 逐段推送 SSE 事件
    //                         try {
    //                             ChatResponse chatResponse = response.chatResponse();
    //                             if(chatResponse == null) {
    //                                 emitter.send(SseEmitter.event().data(END));
    //                             }
    //                             if(chatResponse!=null && !chatResponse.getResults().isEmpty()) {
                        
    //                             List<Generation> results = chatResponse.getResults();   
    //                             //纯对话和工具调用是分离开的，无需考虑先后顺序
    //                             for (Generation generation : results) {
    //                                 String text = generation.getOutput().getText();
    //                                 if(generation!= null && text != null) {
    //                                     emitter.send(SseEmitter.event().data(escapeJson(text)));
    //                                 }
    //                                 // Spring AI 1.1.2 流式模式自动处理工具调用并继续推理，
    //                                 // 最终流中只产出文本 chunk，此处不需要手动处理 toolCalls。
    //                             }
    //                             }
    //                         } catch (IOException e) {
    //                             log.error("SSE 发送失败，中断流", e);
    //                             emitter.completeWithError(e);
    //                         }
    //                     },
    //                     error -> {
    //                         // AI 调用异常 → 推送错误帧后结束
    //                         log.error("AI 流式调用异常", error);
    //                         try {
    //                             emitter.send(SseEmitter.event()
    //                                     .data("{\"error\":\"" + escapeJson(error.getMessage()) + "\",\"code\":5001}"));
    //                             emitter.send(SseEmitter.event().data(END));
    //                             emitter.complete();
    //                         } catch (IOException e) {
    //                             emitter.completeWithError(e);
    //                         }
    //                     },
    //                     () -> {
    //                         // AI 流正常结束
    //                         log.info("AI 流式调用完成");
    //                         try {
    //                             emitter.send(SseEmitter.event().data(END));
    //                             emitter.complete();
    //                         } catch (IOException e) {
    //                             emitter.completeWithError(e);
    //                         }
    //                     });
    // }

    @Override
    public void chatWithToolcall(String content, SseEmitter emitter) {
        log.info("AI SSE 工具调用, content={}", content);
        Long userId = UserHolder.getUserId();
        // 异步执行，不阻塞 Tomcat 容器线程
        //得配个线程池吧
        CompletableFuture.runAsync(() -> {
            try {
                //这里显示插入 userId 到工具调用上下文
                ChatClientRequestSpec prompt = chatClient.prompt().user(content).toolContext(Map.of("userId", userId));
                // Spring AI 1.1.2 call() 已自动处理工具调用：
                //   1. 发消息 → 模型返回 tool call
                //   2. 自动执行注册的 @Tool 方法
                //   3. 工具结果送回模型 → 生成最终回复
                boolean checkPermission = checkPermission(userId, content);
                if (!checkPermission) {
                    emitter.send(SseEmitter.event().data(ASK_PERMISSION));
                    emitter.complete();
                    return;
                }
                String result = prompt.call().content();
                log.info("工具调用完成, result={}", result);

                emitter.send(SseEmitter.event().data(escapeJson(result)));
                emitter.complete();
            } catch (Exception e) {
                log.error("AI SSE 工具调用异常", e);
                try {
                    emitter.send(SseEmitter.event()
                            .data("{\"error\":\"" + escapeJson(e.getMessage()) + "\",\"code\":5001}"));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });
    }

    /**
     * 简易 JSON 转义，防止 error message 中的特殊字符破坏 JSON 格式
     */
    private String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    
    //任务执行前检查是否符合安全策略
    //需要用户审批确认
    private boolean checkPermission(Long userId, String toolName) {
        
        //1.明确的拒绝列表

        //2.匹配用户自定义拒绝规则

        //3.根据结果决定是直接调用还是让用户审批之后再调用
        return true;
    }

}
