package com.wizbl.core.witness;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.ProposalCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.DynamicPropertiesStore;
import com.wizbl.core.db.Manager;
import com.wizbl.protos.Protocol.Proposal;
import com.wizbl.protos.Protocol.Proposal.State;
import com.google.protobuf.ByteString;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testng.collections.Lists;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TEST CLEAR
public class ProposalControllerTest {

  private static final Brte2ApplicationContext context;
  private static final String dbPath = "output_proposal_controller_test";
  private static Manager dbManager = new Manager();
  private static ProposalController proposalController;

  static {
    Args.setParam(new String[]{"-d", dbPath}, Constant.TEST_CONF);
    context = new Brte2ApplicationContext(DefaultConfig.class);
  }

  @BeforeClass
  public static void init() {
    dbManager = context.getBean(Manager.class);
    proposalController = ProposalController
            .createInstance(dbManager);
  }

  @AfterClass
  public static void removeDb() {
    Args.clearParam();
    context.destroy();
    FileUtil.deleteDir(new File(dbPath));
  }

  @Test
  public void testSetDynamicParameters() {

    ProposalCapsule proposalCapsule = new ProposalCapsule(
            Proposal.newBuilder().build());
    Map<Long, Long> parameters = new HashMap<>();
    DynamicPropertiesStore dynamicPropertiesStore = dbManager.getDynamicPropertiesStore();
    long accountUpgradeCostDefault = dynamicPropertiesStore.getAccountUpgradeCost();
    long createAccountFeeDefault = dynamicPropertiesStore.getCreateAccountFee();
    long transactionFeeDefault = dynamicPropertiesStore.getTransactionFee();
    parameters.put(1L, accountUpgradeCostDefault + 1);
    parameters.put(2L, createAccountFeeDefault + 1);
    parameters.put(3L, transactionFeeDefault + 1);
    proposalCapsule.setParameters(parameters);

    proposalController.setDynamicParameters(proposalCapsule);
    Assert.assertEquals(accountUpgradeCostDefault + 1,
            dynamicPropertiesStore.getAccountUpgradeCost());
    Assert.assertEquals(createAccountFeeDefault + 1, dynamicPropertiesStore.getCreateAccountFee());
    Assert.assertEquals(transactionFeeDefault + 1, dynamicPropertiesStore.getTransactionFee());

  }

  @Test
  public void testProcessProposal() {
    ProposalCapsule proposalCapsule = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule.setState(State.PENDING);
    proposalCapsule.setID(1);

    byte[] key = proposalCapsule.createDbKey();
    dbManager.getProposalStore().put(key, proposalCapsule);

    proposalController.processProposal(proposalCapsule);

    try {
      proposalCapsule = dbManager.getProposalStore().get(key);
    } catch (Exception ex) {
    }
    Assert.assertEquals(State.DISAPPROVED, proposalCapsule.getState());

    proposalCapsule.setState(State.PENDING);
    dbManager.getProposalStore().put(key, proposalCapsule);
    for (int i = 0; i < 17; i++) {
      proposalCapsule.addApproval(ByteString.copyFrom(new byte[i]));
    }

    proposalController.processProposal(proposalCapsule);

    try {
      proposalCapsule = dbManager.getProposalStore().get(key);
    } catch (Exception ex) {
    }
    Assert.assertEquals(State.DISAPPROVED, proposalCapsule.getState());

    List<ByteString> activeWitnesses = Lists.newArrayList();
    String prefix = Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1a";
    for (int i = 0; i < 27; i++) {
      activeWitnesses
              .add(ByteString.copyFrom(ByteArray.fromHexString(prefix + (i >= 10 ? i : "0" + i))));
    }
    for (int i = 0; i < 18; i++) {
      proposalCapsule.addApproval(
              ByteString.copyFrom(ByteArray.fromHexString(prefix + (i >= 10 ? i : "0" + i))));
    }
    dbManager.getWitnessScheduleStore().saveActiveWitnesses(activeWitnesses);
    proposalCapsule.setState(State.PENDING);
    dbManager.getProposalStore().put(key, proposalCapsule);
    proposalController.processProposal(proposalCapsule);

    try {
      proposalCapsule = dbManager.getProposalStore().get(key);
    } catch (Exception ex) {
    }
    Assert.assertEquals(State.APPROVED, proposalCapsule.getState());
  }


  @Test
  public void testProcessProposals() {
    ProposalCapsule proposalCapsule1 = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule1.setState(State.APPROVED);
    proposalCapsule1.setID(1);

    ProposalCapsule proposalCapsule2 = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule2.setState(State.DISAPPROVED);
    proposalCapsule2.setID(2);

    ProposalCapsule proposalCapsule3 = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule3.setState(State.PENDING);
    proposalCapsule3.setID(3);
    proposalCapsule3.setExpirationTime(10000L);

    ProposalCapsule proposalCapsule4 = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule4.setState(State.CANCELED);
    proposalCapsule4.setID(4);
    proposalCapsule4.setExpirationTime(11000L);

    ProposalCapsule proposalCapsule5 = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule5.setState(State.PENDING);
    proposalCapsule5.setID(5);
    proposalCapsule5.setExpirationTime(12000L);

    dbManager.getDynamicPropertiesStore().saveLatestProposalNum(5);
    dbManager.getDynamicPropertiesStore().saveNextMaintenanceTime(10000L);
    dbManager.getProposalStore().put(proposalCapsule1.createDbKey(), proposalCapsule1);
    dbManager.getProposalStore().put(proposalCapsule2.createDbKey(), proposalCapsule2);
    dbManager.getProposalStore().put(proposalCapsule3.createDbKey(), proposalCapsule3);
    dbManager.getProposalStore().put(proposalCapsule4.createDbKey(), proposalCapsule4);
    dbManager.getProposalStore().put(proposalCapsule5.createDbKey(), proposalCapsule5);

    proposalController.processProposals();

    try {
      proposalCapsule3 = dbManager.getProposalStore().get(proposalCapsule3.createDbKey());
    } catch (Exception ex) {
    }
    Assert.assertEquals(State.DISAPPROVED, proposalCapsule3.getState());

  }

  @Test
  public void testHasMostApprovals() {
    ProposalCapsule proposalCapsule = new ProposalCapsule(
            Proposal.newBuilder().build());
    proposalCapsule.setState(State.APPROVED);
    proposalCapsule.setID(1);

    List<ByteString> activeWitnesses = Lists.newArrayList();
    for (int i = 0; i < 27; i++) {
      activeWitnesses.add(ByteString.copyFrom(new byte[]{(byte) i}));
    }
    for (int i = 0; i < 18; i++) {
      proposalCapsule.addApproval(ByteString.copyFrom(new byte[]{(byte) i}));
    }

    Assert.assertEquals(true, proposalCapsule.hasMostApprovals(activeWitnesses));

    proposalCapsule.clearApproval();
    for (int i = 1; i < 18; i++) {
      proposalCapsule.addApproval(ByteString.copyFrom(new byte[]{(byte) i}));
    }

    activeWitnesses.clear();
    for (int i = 0; i < 5; i++) {
      activeWitnesses.add(ByteString.copyFrom(new byte[]{(byte) i}));
    }
    proposalCapsule.clearApproval();
    for (int i = 0; i < 3; i++) {
      proposalCapsule.addApproval(ByteString.copyFrom(new byte[]{(byte) i}));
    }
    Assert.assertEquals(true, proposalCapsule.hasMostApprovals(activeWitnesses));


  }


}
