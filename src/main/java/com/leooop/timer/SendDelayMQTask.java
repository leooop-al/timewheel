package com.leooop.timer;

import java.util.Date;

/**
 * @author ziyou.cxf
 * @version : SendDelayMQTask.java, v 0.1 2022年10月24日 22:17 ziyou.cxf Exp $
 */
public class SendDelayMQTask implements TimerTask {

    @Override
    public void run(Timeout timeout) throws Exception {
        // 将延迟消息写入到commitLog中
        System.out.println("im ok");
        System.out.println(new Date());
    }
}
