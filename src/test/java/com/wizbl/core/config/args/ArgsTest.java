/*
 * java-brte2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-brte2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.wizbl.core.config.args;

import com.wizbl.core.Constant;
import com.google.common.collect.Lists;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

@Slf4j
public class ArgsTest {

  @After
  public void destroy() {
    Args.clearParam();
  }

  @Test
  public void get() {
    Args.setParam(new String[]{"-w"}, Constant.TEST_CONF);

    Args args = Args.getInstance();
    Assert.assertEquals("database-acorn-localtest", args.getStorage().getDbDirectory());

      Assert.assertEquals(0, args.getSeedNode().getIpList().size());

    GenesisBlock genesisBlock = args.getGenesisBlock();

      Assert.assertEquals(5, genesisBlock.getAssets().size());

      Assert.assertEquals(4, genesisBlock.getWitnesses().size());

    Assert.assertEquals("0", genesisBlock.getTimestamp());

      Assert.assertEquals("957dc2d350daecc7bb6a38f3938ebde0a0c1cedafe15f0edae4256a2907449f6",
              genesisBlock.getParentHash());

      Assert.assertEquals(
              Lists.newArrayList("c14b4479e1f8312c493aa1fcb8d483b5a1f67f869568e1684cc765876f57e048"),
              args.getLocalWitnesses().getPrivateKeys());

      Assert.assertTrue(args.isNodeDiscoveryEnable());
      Assert.assertTrue(args.isNodeDiscoveryPersist());
      Assert.assertEquals("192.168.2.12", args.getNodeDiscoveryBindIp());
      Assert.assertEquals("110.14.70.250", args.getNodeExternalIp());
      Assert.assertEquals(22060, args.getNodeListenPort());
      Assert.assertEquals(2000, args.getNodeConnectionTimeout());
      Assert.assertEquals(0, args.getActiveNodes().size());
      Assert.assertEquals(30, args.getNodeMaxActiveNodes());
      Assert.assertEquals(20210827, args.getNodeP2pVersion());
      //Assert.assertEquals(30, args.getSyncNodeCount());

      // gRPC network configs checking
      Assert.assertEquals(24160, args.getRpcPort());
      Assert.assertEquals(Integer.MAX_VALUE, args.getMaxConcurrentCallsPerConnection());
      Assert.assertEquals(NettyServerBuilder.DEFAULT_FLOW_CONTROL_WINDOW, args.getFlowControlWindow());
    Assert.assertEquals(60000L, args.getMaxConnectionIdleInMillis());
    Assert.assertEquals(Long.MAX_VALUE, args.getMaxConnectionAgeInMillis());
    Assert.assertEquals(GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE, args.getMaxMessageSize());
    Assert.assertEquals(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, args.getMaxHeaderListSize());
    Assert.assertEquals(1L, args.getAllowCreationOfContracts());
  }
}
