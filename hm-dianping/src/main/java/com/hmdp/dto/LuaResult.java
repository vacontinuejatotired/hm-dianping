package com.hmdp.dto;

import lombok.Data;

@Data
public class LuaResult {
    private Integer code;
    private String message;
    private Object data;

}
