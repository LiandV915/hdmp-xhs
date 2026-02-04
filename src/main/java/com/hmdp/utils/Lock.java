package com.hmdp.utils;

public interface Lock {
    boolean tryLock(long timeoutSecond);
    void unLock();
}
