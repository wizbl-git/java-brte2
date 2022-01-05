package com.wizbl.core.actuator;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.StringUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.TransactionResultCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.exception.ContractExeException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.protos.Contract;
import com.wizbl.protos.Protocol.AccountType;
import com.wizbl.protos.Protocol.Transaction.Result.code;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.*;

import java.io.File;

// TEST CLEAR
@Slf4j
public class CreateAccountActuatorTest {

  private static final Brte2ApplicationContext context;
  private static final String dbPath = "output_CreateAccount_test";
  private static final String OWNER_ADDRESS_FIRST;
  private static final String ACCOUNT_NAME_SECOND = "ownerS";
  private static final String OWNER_ADDRESS_SECOND;
  private static Manager dbManager;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
    context = new Brte2ApplicationContext(DefaultConfig.class);
    OWNER_ADDRESS_FIRST =
            Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
    OWNER_ADDRESS_SECOND =
            Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
  }

  /**
   * Init data.
   */
  @BeforeClass
  public static void init() {
    dbManager = context.getBean(Manager.class);
  }

  /**
   * Release resources.
   */
  @AfterClass
  public static void destroy() {
    Args.clearParam();
    context.destroy();
    if (FileUtil.deleteDir(new File(dbPath))) {
      logger.info("Release resources successful.");
    } else {
      logger.info("Release resources failure.");
    }
  }

  /**
   * create temp Capsule test need.
   */
  @Before
  public void createCapsule() {
//    AccountCapsule ownerCapsule =
//        new AccountCapsule(
//            ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_SECOND)),
//            ByteString.copyFromUtf8(ACCOUNT_NAME_SECOND),
//            AccountType.AssetIssue);
    // firstCreateAccount 테스트 과정에서 ownerAddress에 계정 생성에 필요한 수수료 잔액이 부족하여 테스트 실패가 발생하기에 관련 내용 수정함.
    AccountCapsule ownerCapsule =
            new AccountCapsule(
                    ByteString.copyFromUtf8(ACCOUNT_NAME_SECOND),
                    ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_SECOND)),
                    AccountType.AssetIssue,
                    dbManager.getDynamicPropertiesStore().getCreateNewAccountFeeInSystemContract()
            );
    dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
    dbManager.getAccountStore().delete(ByteArray.fromHexString(OWNER_ADDRESS_FIRST));
  }

  private Any getContract(String ownerAddress, String accountAddress) {
    return Any.pack(
            Contract.AccountCreateContract.newBuilder()
                    .setAccountAddress(ByteString.copyFrom(ByteArray.fromHexString(accountAddress)))
                    .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(ownerAddress)))
                    .build());
  }

  /**
   * Unit test.
   */
  @Test
  public void firstCreateAccount() {
    CreateAccountActuator actuator = new CreateAccountActuator(getContract(OWNER_ADDRESS_SECOND, OWNER_ADDRESS_FIRST), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    try {
      actuator.validate();
      actuator.execute(ret);
      Assert.assertEquals(ret.getInstance().getRet(), code.SUCESS);
      AccountCapsule accountCapsule = dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS_FIRST));
      Assert.assertNotNull(accountCapsule);
      Assert.assertEquals(StringUtil.createReadableString(accountCapsule.getAddress()), OWNER_ADDRESS_FIRST);
    } catch (ContractValidateException e) {
      logger.info(e.getMessage());
      Assert.assertFalse(e instanceof ContractValidateException);
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }

  /**
   * Unit test.
   */
  @Test
  public void secondCreateAccount() {
    CreateAccountActuator actuator =
            new CreateAccountActuator(
                    getContract(OWNER_ADDRESS_SECOND, OWNER_ADDRESS_SECOND), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    try {
      actuator.validate();
      actuator.execute(ret);
    } catch (ContractValidateException e) {
      Assert.assertTrue(e instanceof ContractValidateException);
      AccountCapsule accountCapsule =
              dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS_SECOND));
      Assert.assertNotNull(accountCapsule);
      Assert.assertEquals(
              accountCapsule.getAddress(),
              ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_SECOND)));
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }
}
