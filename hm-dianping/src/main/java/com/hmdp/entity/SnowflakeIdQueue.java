package com.hmdp.entity;

import java.util.concurrent.LinkedBlockingQueue;

public class SnowflakeIdQueue {
    /**
     * 批次ID队列，预先生成批次ID并存储在队列中，确保每次生成ID时都能快速获取一个唯一的批次ID
     */
    private static final LinkedBlockingQueue<Long> BATCH_ID_QUEUE=new LinkedBlockingQueue<Long>();

    /**
     * 负载因子，控制批量生成ID的频率，避免过度生成导致内存占用过高
     * 0.75表示当队列中的ID数量达到容量的75%时触发批量生成新ID的逻辑
     * 没有测试过。仅仅是想到HashMap中扩容时使用的负载因子，感觉可以借鉴一下，暂时设置为0.75，后续可以根据实际情况调整
     */
    private static final Double DEFAULT_LOAD_FACTOR = 0.75;

    private static final Long INIT_CAPICITY = 10000L;

}
