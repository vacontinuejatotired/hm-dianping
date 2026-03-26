package com.hmdp.utils;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.DigestUtils;


/**
 * 自定义的LockFreeRedisScript类，继承自DefaultRedisScript，并重写getSha1方法以实现无锁的Lua脚本执行。
 */
public class LockFreeRedisScript extends DefaultRedisScript {
    private final String cacheSha1;

    public LockFreeRedisScript(String scriptText, Class<?> resultType) {
        setScriptText(scriptText);
        setResultType(resultType);
        this.cacheSha1 = DigestUtils.md5DigestAsHex(scriptText.getBytes());
    }
    @Override
    public String getSha1() {
        return cacheSha1;
    }
    @Override
    public String getScriptAsString() {
        return super.getScriptAsString();
    }
}
