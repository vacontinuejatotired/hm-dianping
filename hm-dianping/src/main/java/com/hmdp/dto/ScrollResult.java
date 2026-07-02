package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动分页结果 — 用于 Feed流 滚动加载（基于 Redis ZSet）
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
