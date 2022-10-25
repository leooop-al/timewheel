package com.leooop.timer;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * @author ziyou.cxf
 * @version : APP.java, v 0.1 2022年10月24日 22:18 ziyou.cxf Exp $
 */
public class APP {

    public static void main(String[] args) throws InterruptedException {
        System.out.println(new Date());
        HashedWheelTimer timer = new HashedWheelTimer();
        timer.newTimeout(new SendDelayMQTask(), 10, TimeUnit.SECONDS);
        Thread.sleep(10000);
        timer.newTimeout(new SendDelayMQTask(), 10, TimeUnit.SECONDS);
        Thread.sleep(12000);
        timer.newTimeout(new SendDelayMQTask(), 10, TimeUnit.SECONDS);
    }
}
