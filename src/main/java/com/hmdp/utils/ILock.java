package com.hmdp.utils;

public interface ILock {
    boolean tryLock(int timeoutSec);

    void unlock();
}
