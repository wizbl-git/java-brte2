package com.wizbl.program;

import ch.qos.logback.classic.Level;
import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.core.Constant;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.core.services.WitnessService;
import com.wizbl.core.services.http.FullNodeHttpApiService;
import com.wizbl.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import com.wizbl.core.services.interfaceOnSolidity.http.solidity.HttpApiOnSolidityService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@Slf4j
public class FullNode {

  /**
   * Start the FullNode.
   * 프로그램 구동 시 포함되는 서비스
   * &emsp; 1. RpcApiService <br/>
   * &emsp; 2. WitnessService(witness node인경우에) <br/>
   * &emsp; 3. FullNodeHttpApiService <br/>
   * &emsp; 4. RpcApiServiceOnSolidity, HttpApiOnSolidityService (dbVersion == 2인 경우에) <br/>
   */
  public static void main(String[] args) {
    logger.info("Full node running.");
    Args.setParam(args, Constant.CONFIG_CONF);
    Args cfgArgs = Args.getInstance();

    ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    root.setLevel(Level.toLevel(cfgArgs.getLogLevel()));

    if (cfgArgs.isHelp()) {
      logger.info("Here is the help message.");
      return;
    }

    if (Args.getInstance().isDebug()) {
      logger.info("in debug mode, it won't check energy time");
    } else {
      logger.info("not in debug mode, it will check energy time");
    }

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.setAllowCircularReferences(false);
    Brte2ApplicationContext context = new Brte2ApplicationContext(beanFactory);
    context.register(DefaultConfig.class);

    context.refresh();
    Application appT = ApplicationFactory.create(context);
    shutdown(appT); // shutdownHook를 설정하는 것이지 실제 종료하는 것이 아님.

    // grpc api server
    RpcApiService rpcApiService = context.getBean(RpcApiService.class);
    appT.addService(rpcApiService);

    if (cfgArgs.isWitness()) {
      appT.addService(new WitnessService(appT, context));
    }

    // http api server
    FullNodeHttpApiService httpApiService = context.getBean(FullNodeHttpApiService.class);
    appT.addService(httpApiService);

    // fullnode and soliditynode fuse together, provide solidity rpc and http server on the fullnode.
    if (Args.getInstance().getStorage().getDbVersion() == 2) {
      RpcApiServiceOnSolidity rpcApiServiceOnSolidity = context.getBean(RpcApiServiceOnSolidity.class);
      appT.addService(rpcApiServiceOnSolidity);
      HttpApiOnSolidityService httpApiOnSolidityService = context.getBean(HttpApiOnSolidityService.class);
      appT.addService(httpApiOnSolidityService);
    }

    appT.initServices(cfgArgs);
    appT.startServices();
    appT.startup();

    rpcApiService.blockUntilShutdown();
  }

  public static void shutdown(final Application app) {
    logger.info("********register application shutdown hook********");
    Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
  }
}
