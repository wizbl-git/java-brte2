package com.wizbl.program;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.overlay.client.DatabaseGrpcClient;
import com.wizbl.core.Constant;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.protos.Protocol.Block;
import com.wizbl.protos.Protocol.DynamicProperties;
import lombok.extern.slf4j.Slf4j;
import org.junit.*;

import java.io.File;

// TEST CLEAR
@Slf4j
public class SolidityNodeTest {

  private static final Brte2ApplicationContext context;

  private static final RpcApiService rpcApiService;
  private static final Application appT;
  private static final String dbPath = "output_witness_test";

  static {
    Args.setParam(new String[]{"-d", dbPath}, Constant.TEST_CONF);
    context = new Brte2ApplicationContext(DefaultConfig.class);
    Args.getInstance().setSolidityNode(true);
    appT = ApplicationFactory.create(context);
    rpcApiService = context.getBean(RpcApiService.class);
  }

  /**
   * init db.
   */
  @BeforeClass
  public static void init() {
    rpcApiService.start();
  }

  /**
   * remo db when after test.
   */
  @AfterClass
  public static void removeDb() {
    Args.clearParam();
    rpcApiService.stop();
    context.destroy();
    File dbFolder = new File(dbPath);
    if (deleteFolder(dbFolder)) {
      logger.info("Release resources successful.");
    } else {
      logger.info("Release resources failure.");
    }
  }

  private static Boolean deleteFolder(File index) {
    if (!index.isDirectory() || index.listFiles().length <= 0) {
      return index.delete();
    }
    for (File file : index.listFiles()) {
      if (null != file && !deleteFolder(file)) {
        return false;
      }
    }
    return index.delete();
  }

  @Test
  public void testSolidityArgs() {
    Assert.assertNotNull(Args.getInstance().getTrustNodeAddr());
    Assert.assertTrue(Args.getInstance().isSolidityNode());
  }

  @Ignore
  @Test
  public void testSolidityGrpcCall() {
    DatabaseGrpcClient databaseGrpcClient = null;
    String addr = Args.getInstance().getTrustNodeAddr();
    try {
      databaseGrpcClient = new DatabaseGrpcClient(addr);
    } catch (Exception e) {
      logger.error("Failed to create database grpc client {}", addr);
    }

    Assert.assertNotNull(databaseGrpcClient);
    DynamicProperties dynamicProperties = databaseGrpcClient.getDynamicProperties();
    Assert.assertNotNull(dynamicProperties);

    Block genisisBlock = databaseGrpcClient.getBlock(0);
    Assert.assertNotNull(genisisBlock);
    Assert.assertFalse(genisisBlock.getTransactionsList().isEmpty());
  }

}
