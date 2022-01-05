package com.wizbl.core.net.node;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.overlay.client.PeerClient;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.common.overlay.server.SyncPool;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.ReflectUtils;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.core.services.WitnessService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class BaseNetTest {

    private final String dbPath;
    private final String dbDirectory;
    private final String indexDirectory;
    private final int port;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    protected Brte2ApplicationContext context;
    protected NodeImpl node;
    protected RpcApiService rpcApiService;
    protected PeerClient peerClient;
    protected ChannelManager channelManager;
    protected SyncPool pool;
    protected Manager manager;
    private Application appT;

    public BaseNetTest(String dbPath, String dbDirectory, String indexDirectory, int port) {
        this.dbPath = dbPath;
        this.dbDirectory = dbDirectory;
        this.indexDirectory = indexDirectory;
        this.port = port;
    }

    @Before
    public void init() {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                logger.info("Full node running.");
                Args.setParam(
                        new String[]{
                                "--output-directory", dbPath,
                                "--storage-db-directory", dbDirectory,
                                "--storage-index-directory", indexDirectory
                        },
                        "config.conf"
                );
                Args cfgArgs = Args.getInstance();
                cfgArgs.setNodeListenPort(port);
                cfgArgs.setNodeDiscoveryEnable(false);
                cfgArgs.getSeedNode().getIpList().clear();
                cfgArgs.setNeedSyncCheck(false);
                cfgArgs.setNodeExternalIp("127.0.0.1");

                context = new Brte2ApplicationContext(DefaultConfig.class);

                if (cfgArgs.isHelp()) {
                    logger.info("Here is the help message");
                    return;
                }
                appT = ApplicationFactory.create(context);
                rpcApiService = context.getBean(RpcApiService.class);
                appT.addService(rpcApiService);
                if (cfgArgs.isWitness()) {
                    appT.addService(new WitnessService(appT, context));
                }
                appT.initServices(cfgArgs);
                appT.startServices();

                node = context.getBean(NodeImpl.class);
                peerClient = context.getBean(PeerClient.class);
                channelManager = context.getBean(ChannelManager.class);
                pool = context.getBean(SyncPool.class);
                manager = context.getBean(Manager.class);
                NodeDelegate nodeDelegate = new NodeDelegateImpl(manager);
                node.setNodeDelegate(nodeDelegate);

                appT.startup();
                rpcApiService.blockUntilShutdown();
            }
        });
        int tryTimes = 1;
        while (tryTimes <= 30 && (node == null || peerClient == null
                || channelManager == null || pool == null)) {
            try {
                logger.info("node:{},peerClient:{},channelManager:{},pool:{}", node, peerClient,
                        channelManager, pool);
                Thread.sleep(1000 * tryTimes);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                ++tryTimes;
            }
        }
    }

    protected Channel createClient(ByteToMessageDecoder decoder)
            throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        // limit the size of receiving buffer to 1024
                        ch.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(256 * 1024));
                        ch.config().setOption(ChannelOption.SO_RCVBUF, 256 * 1024);
                        ch.config().setOption(ChannelOption.SO_BACKLOG, 1024);
                        ch.pipeline()
                                .addLast("readTimeoutHandler", new ReadTimeoutHandler(600, TimeUnit.SECONDS))
                                .addLast("writeTimeoutHandler", new WriteTimeoutHandler(600, TimeUnit.SECONDS));
                        ch.pipeline().addLast("protoPender", new ProtobufVarint32LengthFieldPrepender());
                        ch.pipeline().addLast("lengthDecode", new ProtobufVarint32FrameDecoder());
                        ch.pipeline().addLast("handshakeHandler", decoder);

                        // be aware of channel closing
                        ch.closeFuture();
                    }
                }).option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000)
                .option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
        return b.connect("127.0.0.1", port).sync().channel();
    }

    @After
    public void destroy() {
        executorService.shutdownNow();
        Args.clearParam();
        Collection<PeerConnection> peerConnections = ReflectUtils.invokeMethod(node, "getActivePeer");
        for (PeerConnection peer : peerConnections) {
            peer.close();
        }
        context.destroy();
        node.shutDown();
        appT.shutdownServices();
        appT.shutdown();
        FileUtil.deleteDir(new File(dbPath));
    }
}
