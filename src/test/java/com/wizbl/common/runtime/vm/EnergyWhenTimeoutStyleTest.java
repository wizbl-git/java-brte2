package com.wizbl.common.runtime.vm;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.runtime.TVMTestResult;
import com.wizbl.common.runtime.TVMTestUtils;
import com.wizbl.common.runtime.vm.program.Program.OutOfEnergyException;
import com.wizbl.common.runtime.vm.program.Program.OutOfTimeException;
import com.wizbl.common.storage.DepositImpl;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.exception.ContractExeException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.core.exception.ReceiptCheckErrException;
import com.wizbl.core.exception.VMIllegalException;
import com.wizbl.protos.Protocol.AccountType;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;
import org.testng.Assert;

import java.io.File;

@Slf4j
public class EnergyWhenTimeoutStyleTest {

    private final String dbPath = "output_CPUTimeTest";
    private final long totalBalance = 30_000_000_000_000L;
    private Manager dbManager;
    private Brte2ApplicationContext context;
    private DepositImpl deposit;
    private String OWNER_ADDRESS;
    private Application AppT;

    /**
     * Init data.
     */
    @Before
    public void init() {
        Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
        AppT = ApplicationFactory.create(context);
        OWNER_ADDRESS = Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
        dbManager = context.getBean(Manager.class);
        deposit = DepositImpl.createRoot(dbManager);
        deposit.createAccount(Hex.decode(OWNER_ADDRESS), AccountType.Normal);
        deposit.addBalance(Hex.decode(OWNER_ADDRESS), totalBalance);
        deposit.commit();
    }

    // solidity for endlessLoopTest
    // pragma solidity ^0.4.0;
    //
    // contract TestForEndlessLoop {
    //
    //   uint256 vote;
    //   constructor () public {
    //     vote = 0;
    //   }
    //
    //   function getVote() public constant returns (uint256 _vote) {
    //     _vote = vote;
    //   }
    //
    //   function setVote(uint256 _vote) public {
    //     vote = _vote;
    //     while(true)
    //     {
    //       vote += 1;
    //     }
    //   }
    // }

    @Test
    @Ignore // 수수료 정책 및 계산 방법 확인 후 테스트 진행 예정
    public void endlessLoopTest()
            throws ContractExeException, ContractValidateException, ReceiptCheckErrException, VMIllegalException {

        long value = 0;
        long feeLimit = 1000_000_000L;
        byte[] address = Hex.decode(OWNER_ADDRESS);
        long consumeUserResourcePercent = 0;
        TVMTestResult result = deployEndlessLoopContract(value, feeLimit,
                consumeUserResourcePercent);

        if (null != result.getRuntime().getResult().getException()) {
            long expectEnergyUsageTotal = feeLimit / 100;
            Assert.assertEquals(result.getReceipt().getEnergyUsageTotal(), expectEnergyUsageTotal);
            Assert.assertEquals(dbManager.getAccountStore().get(address).getBalance(),
                    totalBalance - expectEnergyUsageTotal * 100);
            return;
        }
        long expectEnergyUsageTotal = 55107;
        Assert.assertEquals(result.getReceipt().getEnergyUsageTotal(), expectEnergyUsageTotal);
        Assert.assertEquals(dbManager.getAccountStore().get(address).getBalance(),
                totalBalance - expectEnergyUsageTotal * 100);

        byte[] contractAddress = result.getContractAddress();

        /* =================================== CALL setVote(uint256) =================================== */
        String params = "0000000000000000000000000000000000000000000000000000000000000003";
        byte[] triggerData = TVMTestUtils.parseABI("setVote(uint256)", params);
        boolean haveException = false;
        result = TVMTestUtils
                .triggerContractAndReturnTVMTestResult(Hex.decode(OWNER_ADDRESS), contractAddress,
                        triggerData, value, feeLimit, dbManager, null);

        long expectEnergyUsageTotal2 = feeLimit / 100;
        Assert.assertEquals(result.getReceipt().getEnergyUsageTotal(), expectEnergyUsageTotal2);
        Exception exception = result.getRuntime().getResult().getException();
        Assert.assertTrue((exception instanceof OutOfTimeException)
                || (exception instanceof OutOfEnergyException));
        Assert.assertEquals(dbManager.getAccountStore().get(address).getBalance(),
                totalBalance - (expectEnergyUsageTotal + expectEnergyUsageTotal2) * 100);
    }

    public TVMTestResult deployEndlessLoopContract(long value, long feeLimit,
                                                   long consumeUserResourcePercent)
            throws ContractExeException, ReceiptCheckErrException, ContractValidateException, VMIllegalException {
        String contractName = "EndlessLoopContract";
        byte[] address = Hex.decode(OWNER_ADDRESS);
        String ABI = "[{\"constant\":true,\"inputs\":[],\"name\":\"getVote\",\"outputs\":[{\"name\":\"_vote\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_vote\",\"type\":\"uint256\"}],\"name\":\"setVote\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"inputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"constructor\"}]";
        String code = "608060405234801561001057600080fd5b506000808190555060fa806100266000396000f3006080604052600436106049576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680630242f35114604e578063230796ae146076575b600080fd5b348015605957600080fd5b50606060a0565b6040518082815260200191505060405180910390f35b348015608157600080fd5b50609e6004803603810190808035906020019092919050505060a9565b005b60008054905090565b806000819055505b60011560cb576001600080828254019250508190555060b1565b505600a165627a7a72305820290a38c9bbafccaf6c7f752ab56d229e354da767efb72715ee9fdb653b9f4b6c0029";
        String libraryAddressPair = null;

        return TVMTestUtils
                .deployContractAndReturnTVMTestResult(contractName, address, ABI, code,
                        value,
                        feeLimit, consumeUserResourcePercent, libraryAddressPair,
                        dbManager, null);
    }

    /**
     * Release resources.
     */
    @After
    public void destroy() {
        Args.clearParam();
        AppT.shutdownServices();
        AppT.shutdown();
        context.destroy();
        if (FileUtil.deleteDir(new File(dbPath))) {
            logger.info("Release resources successful.");
        } else {
            logger.info("Release resources failure.");
        }
    }

}
