package org.example;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class PublishBoot {
    private static final Logger log = LogManager.getLogger(PublishBoot.class);
    //使用了udp协议，端口号9000，本地回环地址
    private static final String CHANNEL = "aeron:udp?endpoint=localhost:9000";
    //流标识，pub 和 sub 使用相同的stream
    private static final int STREAM_ID = 1001;
    //发送10条消息
    private static final int MESSAGE_COUNT = 10;

    public static void main(String[] args) throws InterruptedException {
        log.info("=================");
        log.info(" Aeron Publisher启动中...");
        log.info("Channel: " + CHANNEL);
        log.info("Stream ID: " + STREAM_ID);
        log.info("=================\n");



    //启动删除旧的共享内存文件
        MediaDriver.Context context = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                ;

        try(
                MediaDriver driver = MediaDriver.launch(context);
                //链接aeron
                Aeron aeron = Aeron.connect();

                //创建一个pub
                Publication publication = aeron.addPublication(CHANNEL,STREAM_ID);
                ){
            log.info("MediaDriver已启动");
            log.info("Aeron 客户端已链接");
            log.info("Publication 已创建\n");

            while (!publication.isConnected()){
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100));
            }

            //申明并创建一块儿缓冲区，长度256，aerona 是一个aeron下的高性能零拷贝的组件库
            final UnsafeBuffer unsafeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

            long result;

            //使用offer 进行发送，根据返回值确定发送结果
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                final String msg = "Hello Aeron "+i;
                byte[] msgBytes = msg.getBytes();
                unsafeBuffer.putBytes(0,msgBytes);
                int length = msgBytes.length;
                log.info("发送消息 : {} + msg: {}",i,msg);
                do {
                     result = publication.offer(unsafeBuffer,0,length);
                    if (result<0){
                        //发送失败处理
                        if (result == Publication.BACK_PRESSURED){
                            log.info("发生背压，等待重试");
                        } else if (result == Publication.NOT_CONNECTED) {
                            log.info("订阅者断开链接");
                        }else {
                            log.info("发送失败，错误码：{}",result);
                        }
                        //让出CPU 等待重试
                        Thread.yield();
                    }
                }while (result < 0L);

                log.info("发送成功，result ：{}",result);

                //设置发送间隔
                Thread.sleep(500);
            }
            log.info("=============================");
            log.info("所有消息发送完成");
            log.info("=============================");

        }
    }
}
