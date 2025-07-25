package com.hmdp.utils;


public interface ILock {
    /**
     * 拿到锁
     * @param timeoutSec 持有时间
     * @return
     */
    boolean tryLock(long timeoutSec);

    void unlock();
}
