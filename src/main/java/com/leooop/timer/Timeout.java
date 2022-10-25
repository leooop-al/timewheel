package com.leooop.timer;

/**
 * @author ziyou.cxf
 * @version : Timeout.java, v 0.1 2022年10月23日 16:47 ziyou.cxf Exp $
 */
public interface Timeout {

    /**
     * returns the {@link Timer} that created this handle.
     * @return
     */
    Timer timer();

    /**
     * returns the {@link TimerTask} which is associated with this handle.
     *
     * @return
     */
    TimerTask task();

    /**
     * Returns {@code true} if and only of the @{@link TimerTask} associated
     * with this handle has been expired
     *
     * @return
     */
    boolean isExpired();

    /**
     * Returns {@code true} if and only of the @{@link TimerTask} associated
     * with this handle has been cancelled
     *
     * @return
     */
    boolean isCancelled();

    /**
     * Attempts to cancel the {@link TimerTask} associated with this handle.
     * If the task has been executed or cancelled already, it will return with
     * no side effect.
     *
     * @return True if the cancellation completed successfully, otherwise false
     */
    boolean cancel();
}
