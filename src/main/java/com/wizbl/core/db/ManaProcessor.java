package com.wizbl.core.db;

import lombok.extern.slf4j.Slf4j;
import com.wizbl.common.utils.StringUtil;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.exception.*;

@Slf4j
public class ManaProcessor extends ResourceProcessor {
	public ManaProcessor(Manager manager) {
		super(manager);
	}

	@Override
	public void updateUsage(AccountCapsule accountCapsule) {
		long now = dbManager.getWitnessController().getHeadSlot();
		updateUsage(accountCapsule, now);
	}

	public void consume(TransactionCapsule trx, TransactionTrace trace)
			throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
		this.spend(trx, trace);
	}

	private void updateUsage(AccountCapsule accountCapsule, long now) {
		long oldManaUsage = accountCapsule.getManaUsage();
		long latestSpendTime = accountCapsule.getLatestSpendTime();
		accountCapsule.setManaUsage(increase(oldManaUsage, 0, latestSpendTime, now));
	}

	public boolean spend(TransactionCapsule trx, TransactionTrace trace)
			throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
		long byteSize = trace.getReceipt().getNetUsage();
		long energy = trace.getReceipt().getEnergyUsageTotal();
		byte[] address
				= TransactionCapsule.getOwner(trx.getInstance().getRawData().getContract(0));

		AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
		if (accountCapsule == null) {
			throw new ContractValidateException("Use fee failed.");
		}

		long now = dbManager.getWitnessController().getHeadSlot();

		String strContractType = trx.getInstance().getRawData().getContract(0).getType().toString();

		switch (strContractType)
		{
		case "AccountCreateContract":
			break;
		default:
			if ((useMana(accountCapsule, byteSize + energy, now))) {
				return true;
			} else {
				System.out.println("Spend Fee (WBL)");
				try {
					useFeeByMana(accountCapsule, byteSize, energy, trace, now);
				} catch (BalanceInsufficientException e) {
					throw new ContractValidateException(e.getMessage());
				}
			}
			break;
		}

		return false;
	}

	protected boolean useMana(AccountCapsule accountCapsule, long mana, long now) {
		long freeManaLimit = dbManager.getDynamicPropertiesStore().getFreeManaLimit();
//		long totalManaUsage = accountCapsule.getTotalManaUsage();
		long manaUsage = accountCapsule.getManaUsage();
		long latestSpendTime = accountCapsule.getLatestSpendTime();
		long newManaUsage = increase(manaUsage, 0, latestSpendTime, now);

		if (mana > (freeManaLimit - newManaUsage)) {
			return false;    // spend WBL
		}

//		long manaUsage = accountCapsule.getManaUsage();

		latestSpendTime = now;
		long latestOperationTime = dbManager.getHeadBlockTimeStamp();
		newManaUsage = increase(newManaUsage, mana, latestSpendTime, now);

		accountCapsule.setManaUsage(newManaUsage);
		accountCapsule.setLatestOperationTime(latestOperationTime);
		accountCapsule.setLatestSpendTime(latestSpendTime);

		dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
		return true;
	}

	private boolean useFeeByMana(AccountCapsule accountCapsule
			, long byteSize, long energy, TransactionTrace trace, long now)
			throws BalanceInsufficientException {

		long mana = byteSize + energy;
		long freeManaLimit = dbManager.getDynamicPropertiesStore().getFreeManaLimit();
//		long totalManaUsage = accountCapsule.getTotalManaUsage();
		long latestSpendTime = accountCapsule.getLatestSpendTime();
		long manaUsage = accountCapsule.getManaUsage();
		long newManaUsage = increase(manaUsage, 0, latestSpendTime, now);

		long accountManaLeft = freeManaLimit - accountCapsule.getManaUsage();
		long dynamicManaFee = dbManager.getDynamicPropertiesStore().getManaFee();    // wbl / 1_000_000 : mana = 1:1

		if (dynamicManaFee > 0) {
			/*
			need set wbl per mana.
			wblPerMana = ......
			*/
		}
		long manaFee = Math.abs((mana - accountManaLeft) * dynamicManaFee);

		latestSpendTime = now;
		long latestOperationTime = dbManager.getHeadBlockTimeStamp();
		newManaUsage = increase(newManaUsage, accountManaLeft, latestSpendTime, now);

		accountCapsule.setManaUsage(newManaUsage);
		accountCapsule.setLatestOperationTime(latestOperationTime);
		accountCapsule.setLatestSpendTime(latestSpendTime);

		trace.getReceipt().setManaUsage(accountManaLeft);
		trace.getReceipt().setManaFee(manaFee);

		long balance = accountCapsule.getBalance();
		if (balance < manaFee) {
			throw new BalanceInsufficientException(
					StringUtil.createReadableString(accountCapsule.createDbKey()) + " insufficient balance");
		}
		accountCapsule.setBalance(balance - manaFee);

		dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
		dbManager.adjustBalance(accountCapsule, -manaFee);
		dbManager.adjustBalance(dbManager.getAccountStore().getBandWidthFeeAccount().createDbKey(), byteSize);
		dbManager.adjustBalance(dbManager.getAccountStore().getEnergyFeeAccount().createDbKey(), energy);
/*
    	long fee = dbManager.getDynamicPropertiesStore().getManaFee() * mana;

		if (consumeFee(accountCapsule, fee)) {
			trace.setManaBill(0, fee);
			dbManager.getDynamicPropertiesStore().addTotalTransactionCost(fee);
			return true;
		} else {
			return false;
		}
*/
		return true;
	}
}
