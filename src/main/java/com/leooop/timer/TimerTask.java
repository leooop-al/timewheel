package com.leooop.timer;

/**
 * @author ziyou.cxf
 * @version : TimerTask.java, v 0.1 2022年10月23日 16:47 ziyou.cxf Exp $
 * @desc 定时任务
 */
public interface TimerTask {

    /**
     * Executed after the delay specified with
     * 在MQ中，就可以作为将延迟消息投递到正确队列的任务
     *
     * @param timeout
     * @throws Exception
     */
    void run(Timeout timeout) throws Exception;
}
