package com.wizbl.core.services;

import com.wizbl.core.net.node.BaseNetTest;
import lombok.extern.slf4j.Slf4j;

// JUnit4 스타일로 다시 작성할 필요가 있음.
@Slf4j
public class NodeInfoServiceTest extends BaseNetTest {
    public NodeInfoServiceTest(String dbPath, String dbDirectory, String indexDirectory, int port) {
        super(dbPath, dbDirectory, indexDirectory, port);
    }

//    private static final String dbPath = "output-nodeImplTest-nodeinfo";
//    private static final String dbDirectory = "db_nodeinfo_test";
//    private static final String indexDirectory = "index_nodeinfo_test";
//    private final static int port = 15899;
//
//    private NodeInfoService nodeInfoService;
//    private WitnessProductBlockService witnessProductBlockService;
//
//    public NodeInfoServiceTest() {
//        super(dbPath, dbDirectory, indexDirectory, port);
//    }
//
//    public static void main(String[] args) {
//        NodeInfoServiceTest test = new NodeInfoServiceTest();
//        test.testGrpc();
//    }
//
//    @Test
//    public void test() {
//        nodeInfoService = context.getBean("nodeInfoService", NodeInfoService.class);
//        witnessProductBlockService = context.getBean(WitnessProductBlockService.class);
//        BlockCapsule blockCapsule1 = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 100, ByteString.EMPTY);
//        BlockCapsule blockCapsule2 = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 200, ByteString.EMPTY);
//        witnessProductBlockService.validWitnessProductTwoBlock(blockCapsule1);
//        witnessProductBlockService.validWitnessProductTwoBlock(blockCapsule2);
//        NodeInfo nodeInfo = nodeInfoService.getNodeInfo();
//        Assert.assertEquals(nodeInfo.getConfigNodeInfo().getCodeVersion(), Version.getVersion());
//        Assert.assertEquals(nodeInfo.getCheatWitnessInfoMap().size(), 1);
//        logger.info("{}", JSON.toJSONString(nodeInfo));
//    }
//
//    public void testGrpc() {
//        String fullnode = Configuration.getByPath(Constant.TEST_CONF).getStringList("fullnode.ip.list").get(0);
//        WalletBlockingStub walletStub = WalletGrpc.newBlockingStub(ManagedChannelBuilder.forTarget(fullnode)
//                .usePlaintext(true)
//                .build());
//        logger.info("getNodeInfo: {}", walletStub.getNodeInfo(EmptyMessage.getDefaultInstance()));
//    }
}
