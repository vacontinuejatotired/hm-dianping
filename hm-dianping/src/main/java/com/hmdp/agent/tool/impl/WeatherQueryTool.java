package com.hmdp.agent.tool.impl;

import com.hmdp.annotation.TargetTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import lombok.extern.slf4j.Slf4j;

@TargetTool(active = true)
@Slf4j
public class WeatherQueryTool {

    @Tool(description = "查询天气，参数为城市，仅用于测试工具调用")
    public String queryWeather(@ToolParam(description = "城市名称,例如北京") String city) {
        log.info("queryWeather: {}", city);
        return "The weather in " + city + " is sunny";
    }
}
