package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final String LOGIN_USERINFO_MAP = "login:userinfo:";
    public static final String LOGIN_REFRESH_USER_KEY = "login:refresh:user:";
    public static final String LOGIN_VALID_VERSION_KEY = "login:valid:version:";
    public static final Long LOGIN_USER_TTL = 36000L;
    public static final String TOKEN_VERSION_KEY = "token:refresh:version:";
    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long LOGIN_TOKEN_TTL_SECONDS = 1800L;
    public static final Long LOGIN_REFRESHTOKEN_TTL_SECONDS = (7 * 24 * 60 * 60L);
    //过期时间暂时设置为1分钟方便测试
    public static final Long LOGIN_JWT_TTL_MINUTES = 1L;
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String CACHE_SHOPTYPE_KEY = "cache:shopType";
}
