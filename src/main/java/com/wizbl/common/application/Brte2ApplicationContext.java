package com.wizbl.common.application;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.wizbl.common.overlay.discover.DiscoverServer;
import com.wizbl.common.overlay.discover.node.NodeManager;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.core.db.Manager;

public class Brte2ApplicationContext extends AnnotationConfigApplicationContext {

  public Brte2ApplicationContext() {
  }

  public Brte2ApplicationContext(DefaultListableBeanFactory beanFactory) {
    super(beanFactory);
  }

  public Brte2ApplicationContext(Class<?>... annotatedClasses) {
    super(annotatedClasses);
  }

  public Brte2ApplicationContext(String... basePackages) {
    super(basePackages);
  }

  @Override
  public void destroy() {

    Application appT = ApplicationFactory.create(this);
    appT.shutdownServices();
    appT.shutdown();

    DiscoverServer discoverServer = getBean(DiscoverServer.class);
    discoverServer.close();
    ChannelManager channelManager = getBean(ChannelManager.class);
    channelManager.close();
    NodeManager nodeManager = getBean(NodeManager.class);
    nodeManager.close();
    
    Manager dbManager = getBean(Manager.class);
    dbManager.stopRepushThread();

    super.destroy();
  }
}
