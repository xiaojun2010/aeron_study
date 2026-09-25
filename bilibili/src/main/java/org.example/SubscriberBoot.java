package org.example;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;

public class SubscriberBoot {
    private static final Logger log = LogManager.getLogger(SubscriberBoot.class);
    //使用了udp协议，端口号9000，本地回环地址
    private static final String CHANNEL = "aeron:udp?endpoint=localhost:9000";
    //流标识，pub 和 sub 使用相同的stream
    private static final int STREAM_ID = 1001;
    private static final int MESSAGE_COUNT = 10;

    public static void main(String[] args) {
        log.info("=================");
        log.info(" Aeron Subscriber 启动中...");
        log.info("Channel: " + CHANNEL);
        log.info("Stream ID: " + STREAM_ID);
        log.info("=================\n");

        //一个机器上只能跑一个 MediaDriver ，所以这里使用嵌入式的 MediaDriver
        MediaDriver.Context context = new MediaDriver.Context()
                .dirDeleteOnStart(true);

        //启动 MediaDriver 接收消息
        try(
                MediaDriver driver = MediaDriver.launch(context);
                //链接aeron
                Aeron aeron = Aeron.connect();
                //创建 Subscripition
                Subscription subscription = aeron.addSubscription(CHANNEL,STREAM_ID);
                ){
            log.info("MediaDriver已启动");
            log.info("Aeron 客户端已链接");
            log.info("Subscription 实例已创建\n");

            //消息处理回调，基于 一个FragmentHandler 的实例
            final FragmentHandler fragmentHandler = new FragmentHandler() {
                @Override
                public void onFragment(DirectBuffer directBuffer, int offset, int length, Header header) {
                    byte[] msgBytes = new byte[length];
                    directBuffer.getBytes(offset,msgBytes);
                    String message = new String(msgBytes, StandardCharsets.UTF_8);
                    log.info("=================");
                    log.info("接收到消息：sessionId = {},StreamId = {}, position = {}, 内容:{}，长度:{} 字节 ",
                            header.sessionId(),
                            header.streamId(),
                            header.position(),
                            message,
                            length);
                    log.info("=================");
                }
            };


            log.info("等待消息....\n");
            int messageCount = 0;
            while (true){
                //需要通过poll方式持续拉取消息
                int fragmentsRead = subscription.poll(fragmentHandler,MESSAGE_COUNT);
                if (fragmentsRead == 0){
                    //没有消息，先让出CPU 时间片
                    Thread.yield();
                }else {
                    messageCount += fragmentsRead;
                    log.info("已经接收到消息总数 fragmentsRead ： {} ",messageCount);
                }
                if (messageCount >= MESSAGE_COUNT){
                    break;
                }
            }
        }
    }
}
