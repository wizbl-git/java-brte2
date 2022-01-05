package com.wizbl.core.db;

import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.Parameter.AdaptiveResourceLimitConstants;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.exception.AccountResourceInsufficientException;
import com.wizbl.core.exception.BalanceInsufficientException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.core.exception.TooBigTransactionResultException;

abstract class ResourceProcessor {

  protected Manager dbManager;
  protected long precision;
  protected long windowSize;
  protected long averageWindowSize;

  public ResourceProcessor(Manager manager) {
    this.dbManager = manager;
    this.precision = ChainConstant.PRECISION;
    this.windowSize = ChainConstant.WINDOW_SIZE_MS / ChainConstant.BLOCK_PRODUCED_INTERVAL;
    this.averageWindowSize =
        AdaptiveResourceLimitConstants.PERIODS_MS / ChainConstant.BLOCK_PRODUCED_INTERVAL;
  }

  abstract void updateUsage(AccountCapsule accountCapsule);

  abstract void consume(TransactionCapsule trx, TransactionTrace trace)
      throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException;

  protected long increase(long lastUsage, long usage, long lastTime, long now) {
    return increase(lastUsage, usage, lastTime, now, windowSize);
  }

  protected long increase(long lastUsage, long usage, long lastTime, long now, long windowSize) {
    long averageLastUsage = divideCeil(lastUsage * precision, windowSize);
    long averageUsage = divideCeil(usage * precision, windowSize);

    if (lastTime != now) {
      assert now > lastTime;
      if (lastTime + windowSize > now) {
        long delta = now - lastTime;
        double decay = (windowSize - delta) / (double) windowSize;
        averageLastUsage = Math.round(averageLastUsage * decay);
      } else {
        averageLastUsage = 0;
      }
    }
    averageLastUsage += averageUsage;
    return getUsage(averageLastUsage, windowSize);
  }

  private long divideCeil(long numerator, long denominator) {
    return (numerator / denominator) + ((numerator % denominator) > 0 ? 1 : 0);
  }

  private long getUsage(long usage) {
    return usage * windowSize / precision;
  }

  private long getUsage(long usage, long windowSize) {
    return usage * windowSize / precision;
  }

  /**
   * account가 보유하고 있는 Balance(코인보유량)에서 수수료를 차감함.
   * 이 때 account에서 차감되는 수수료는 Squirrel 계정에 포함됨.
   *
   * @param accountCapsule
   * @param fee
   * @return
   */
  protected boolean consumeFee(AccountCapsule accountCapsule, long fee) {
    try {
      long latestOperationTime = dbManager.getHeadBlockTimeStamp();
      accountCapsule.setLatestOperationTime(latestOperationTime);
      dbManager.adjustBalance(accountCapsule, -fee);
      dbManager.adjustBalance(this.dbManager.getAccountStore().getSquirrel().createDbKey(), +fee);
      return true;
    } catch (BalanceInsufficientException e) {
      return false;
    }
  }
}
