package com.hmdp.utils;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

@Component
public class RedisIdWorker {
    public static long nextId() {
        Snowflake snowflake = IdUtil.getSnowflake();
        return snowflake.nextId();
    }
}
