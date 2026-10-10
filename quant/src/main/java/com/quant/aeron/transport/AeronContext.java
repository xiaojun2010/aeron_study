package com.quant.aeron.transport;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;

/**
 * 内嵌 MediaDriver + 单一 Aeron 客户端的生命周期容器。
 * 真实部署通常使用独立 MediaDriver 进程 + 多个客户端共享 aeron.dir。
 */
public final class AeronContext implements AutoCloseable {

    private final MediaDriver mediaDriver;
    private final Aeron aeron;

    private AeronContext(MediaDriver mediaDriver, Aeron aeron) {
        this.mediaDriver = mediaDriver;
        this.aeron = aeron;
    }

    public static AeronContext embedded() {
        return embedded(ThreadingMode.SHARED);
    }

    public static AeronContext embedded(ThreadingMode mode) {
        MediaDriver.Context driverCtx = new MediaDriver.Context()
                .threadingMode(mode)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);
        MediaDriver md = MediaDriver.launchEmbedded(driverCtx);
        Aeron.Context aeronCtx = new Aeron.Context()
                .aeronDirectoryName(md.aeronDirectoryName());
        Aeron aeron = Aeron.connect(aeronCtx);
        return new AeronContext(md, aeron);
    }

    public static AeronContext connect(String aeronDir) {
        Aeron.Context ctx = new Aeron.Context()
                .aeronDirectoryName(aeronDir == null ? CommonContext.getAeronDirectoryName() : aeronDir);
        return new AeronContext(null, Aeron.connect(ctx));
    }

    public Aeron aeron() {
        return aeron;
    }

    @Override
    public void close() {
        CloseHelper.quietClose(aeron);
        CloseHelper.quietClose(mediaDriver);
    }
}
