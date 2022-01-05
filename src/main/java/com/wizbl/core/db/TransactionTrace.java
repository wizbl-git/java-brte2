package com.wizbl.core.db;

import com.wizbl.common.runtime.Runtime;
import com.wizbl.common.runtime.RuntimeImpl;
import com.wizbl.common.runtime.vm.program.InternalTransaction;
import com.wizbl.common.runtime.vm.program.Program.*;
import com.wizbl.common.runtime.vm.program.ProgramResult;
import com.wizbl.common.runtime.vm.program.invoke.ProgramInvokeFactoryImpl;
import com.wizbl.common.storage.DepositImpl;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.ContractCapsule;
import com.wizbl.core.capsule.ReceiptCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.*;
import com.wizbl.protos.Contract.TriggerSmartContract;
import com.wizbl.protos.Protocol.Transaction;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import com.wizbl.protos.Protocol.Transaction.Result.contractResult;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Objects;

import static com.wizbl.common.runtime.vm.program.InternalTransaction.TrxType.*;

/**
 * TransactionTrace 클래스는 transaction이 실행되는 과정에서 발생하는 비용의 계산, transaction 실행 후의 산정된 비용의 지불,
 * 비용된 지불에 대한 지불내용을 확인(영수증)하는 메소드를 관리하고 있음.
 */
@Slf4j(topic = "TransactionTrace")
public class TransactionTrace {

    private TransactionCapsule trx;

    private ReceiptCapsule receipt;

    private Manager dbManager;

    private Runtime runtime;

    private EnergyProcessor energyProcessor;

    private InternalTransaction.TrxType trxType;

    private long txStartTimeInMs;
    @Getter
    @Setter
    private TimeResultType timeResultType = TimeResultType.NORMAL;

    /**
     * TransactionTrace 클래스의 새로운 객체를 생성함.<br/>
     * @param trx
     * @param dbManager
     */
    public TransactionTrace(TransactionCapsule trx, Manager dbManager) {
        this.trx = trx;
        Transaction.Contract.ContractType contractType = this.trx.getInstance().getRawData().getContract(0).getType();
        switch (contractType.getNumber()) {
            case ContractType.TriggerSmartContract_VALUE:
                trxType = TRX_CONTRACT_CALL_TYPE;
                break;
            case ContractType.CreateSmartContract_VALUE:
                trxType = TRX_CONTRACT_CREATION_TYPE;
                break;
            default:
                trxType = TRX_PRECOMPILED_TYPE;
        }

        this.dbManager = dbManager;
        this.receipt = new ReceiptCapsule(Sha256Hash.ZERO_HASH);

        this.energyProcessor = new EnergyProcessor(this.dbManager);
    }

    public TransactionCapsule getTrx() {
        return trx;
    }

    private boolean needVM() {
        return this.trxType == TRX_CONTRACT_CALL_TYPE || this.trxType == TRX_CONTRACT_CREATION_TYPE;
    }

    /**
     * pre transaction check <br/>
     * @param blockCap
     */
    public void init(BlockCapsule blockCap) {
        txStartTimeInMs = System.currentTimeMillis();
        DepositImpl deposit = DepositImpl.createRoot(dbManager);
        runtime = new RuntimeImpl(this, blockCap, deposit, new ProgramInvokeFactoryImpl());
    }

    public void checkIsConstant() throws ContractValidateException, VMIllegalException {
        if (runtime.isCallConstant()) {
            throw new VMIllegalException("cannot call constant method ");
        }
    }

    //set bill
    public void setBill(long energyUsage) {
        if (energyUsage < 0) {
            energyUsage = 0L;
        }
        receipt.setEnergyUsageTotal(energyUsage);
    }

    /**
     * set net bill <br/>
     * NetUsage , NetFee 정보가 ReceiptCapsule 객체에 저장됨.
     * @param netUsage
     * @param netFee
     */
    public void setNetBill(long netUsage, long netFee) {
        receipt.setNetUsage(netUsage);
        receipt.setNetFee(netFee);
    }


    public void exec()
            throws ContractExeException, ContractValidateException, VMIllegalException {
        /*  VM execute  */
        runtime.execute();
        runtime.go();

        if (TRX_PRECOMPILED_TYPE != runtime.getTrxType()) {
            if (contractResult.OUT_OF_TIME.equals(receipt.getResult())) {
                setTimeResultType(TimeResultType.OUT_OF_TIME);
            } else if (System.currentTimeMillis() - txStartTimeInMs
                    > Args.getInstance().getLongRunningTime()) {
                setTimeResultType(TimeResultType.LONG_RUNNING);
            }
        }
    }

    public void finalization() throws ContractExeException {
        try {
            pay();
        } catch (BalanceInsufficientException e) {
            throw new ContractExeException(e.getMessage());
        }
        runtime.finalization();
    }

    /**
     * pay actually bill(include ENERGY and storage).
     */
    public void pay() throws BalanceInsufficientException {
        byte[] originAccount;
        byte[] callerAccount;
        long percent = 0;
        long originEnergyLimit = 0;
        switch (trxType) {
            case TRX_CONTRACT_CREATION_TYPE:
                callerAccount = TransactionCapsule.getOwner(trx.getInstance().getRawData().getContract(0));
                originAccount = callerAccount;
                break;
            case TRX_CONTRACT_CALL_TYPE:
                TriggerSmartContract callContract = ContractCapsule
                        .getTriggerContractFromTransaction(trx.getInstance());
                ContractCapsule contractCapsule =
                        dbManager.getContractStore().get(callContract.getContractAddress().toByteArray());

                callerAccount = callContract.getOwnerAddress().toByteArray();
                originAccount = contractCapsule.getOriginAddress();
                percent = Math
                        .max(Constant.ONE_HUNDRED - contractCapsule.getConsumeUserResourcePercent(), 0);
                percent = Math.min(percent, Constant.ONE_HUNDRED);
                originEnergyLimit = contractCapsule.getOriginEnergyLimit();
                break;
            default:
                return;
        }

        // originAccount Percent = 30%
/*  Remark jc.kang - testtesttest.  에너지 fee 사용하지 않도록 주석. 검증 후 해당 코멘트 삭제할 것.
    AccountCapsule origin = dbManager.getAccountStore().get(originAccount);
    AccountCapsule caller = dbManager.getAccountStore().get(callerAccount);
    receipt.payEnergyBill(
        dbManager,
        origin,
        caller,
        percent, originEnergyLimit,
        energyProcessor,
        dbManager.getWitnessController().getHeadSlot());
*/
    }

    public boolean checkNeedRetry() {
        if (!needVM()) {
            return false;
        }
        if (!trx.getContractRet().equals(contractResult.OUT_OF_TIME)
                && receipt.getResult().equals(contractResult.OUT_OF_TIME)) {
            return true;
        }
        return false;
    }

    public void check() throws ReceiptCheckErrException {
        if (!needVM()) {
            return;
        }
        if (Objects.isNull(trx.getContractRet())) {
            throw new ReceiptCheckErrException("null resultCode");
        }
        if (!trx.getContractRet().equals(receipt.getResult())) {
            logger.info(
                    "this tx resultCode in received block: {}\nthis tx resultCode in self: {}",
                    trx.getContractRet(), receipt.getResult());
            throw new ReceiptCheckErrException("Different resultCode");
        }
    }

    public ReceiptCapsule getReceipt() {
        return receipt;
    }

    public void setResult() {
        if (!needVM()) {
            return;
        }
        RuntimeException exception = runtime.getResult().getException();
        if (Objects.isNull(exception) && StringUtils
                .isEmpty(runtime.getRuntimeError()) && !runtime.getResult().isRevert()) {
            receipt.setResult(contractResult.SUCCESS);
            return;
        }
        if (runtime.getResult().isRevert()) {
            receipt.setResult(contractResult.REVERT);
            return;
        }
        if (exception instanceof IllegalOperationException) {
            receipt.setResult(contractResult.ILLEGAL_OPERATION);
            return;
        }
        if (exception instanceof OutOfEnergyException) {
            receipt.setResult(contractResult.OUT_OF_ENERGY);
            return;
        }
        if (exception instanceof BadJumpDestinationException) {
            receipt.setResult(contractResult.BAD_JUMP_DESTINATION);
            return;
        }
        if (exception instanceof OutOfTimeException) {
            receipt.setResult(contractResult.OUT_OF_TIME);
            return;
        }
        if (exception instanceof OutOfMemoryException) {
            receipt.setResult(contractResult.OUT_OF_MEMORY);
            return;
        }
        if (exception instanceof PrecompiledContractException) {
            receipt.setResult(contractResult.PRECOMPILED_CONTRACT);
            return;
        }
        if (exception instanceof StackTooSmallException) {
            receipt.setResult(contractResult.STACK_TOO_SMALL);
            return;
        }
        if (exception instanceof StackTooLargeException) {
            receipt.setResult(contractResult.STACK_TOO_LARGE);
            return;
        }
        if (exception instanceof JVMStackOverFlowException) {
            receipt.setResult(contractResult.JVM_STACK_OVER_FLOW);
            return;
        }
        receipt.setResult(contractResult.UNKNOWN);
    }

    public String getRuntimeError() {
        return runtime.getRuntimeError();
    }

    public ProgramResult getRuntimeResult() {
        return runtime.getResult();
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public enum TimeResultType {
        NORMAL,
        LONG_RUNNING,
        OUT_OF_TIME
    }
}
