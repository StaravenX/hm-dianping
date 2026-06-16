package com.hmdp.utils;

import org.springframework.stereotype.Component;

@Component
public interface ILock {

    public boolean tryLock(Long timeoutSec);

    public void unlock();

}
