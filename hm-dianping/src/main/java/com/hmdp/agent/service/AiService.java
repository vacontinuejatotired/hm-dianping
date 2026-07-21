package com.hmdp.agent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
* AI服务接口 — 对接 AI 对话能力
*/
public interface AiService {

   /**
    * 普通模式：同步调用 AI，等待完整回复后返回
    */
   String chatReturnStringResult(String content);

   /**
    * 流式模式：通过 SSE (Server-Sent Events) 逐段推送 AI 回复
    *
    * @param content 用户输入
    * @param emitter SSE 发射器，用于推送逐段结果。方法内完成 subscribe/complete 全流程
    */
   // void chatStream(String content, SseEmitter emitter);
   
   /**
    * 流式模式：通过 SSE (Server-Sent Events) 逐段推送 AI 回复，包含工具调用,这里做的是伪流式，提前调用工具，再推送结果
    */
   void chatWithToolcall(String content, SseEmitter emitter);
}
