package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.concurrency.LockContext;

import java.util.function.Function;

// Instrumented version of ARIESRecoveryManager for testing, without locking so that tests pass without HW4.
class ARIESRecoveryManagerNoLocking extends ARIESRecoveryManager {
    long transactionCounter = 0L;

    ARIESRecoveryManagerNoLocking(LockContext dbContext,
                                  Function<Long, Transaction> newTransaction) {
        super(dbContext, newTransaction, null, null, true);
        super.updateTransactionCounter = x -> transactionCounter = x;
        super.getTransactionCounter = () -> transactionCounter;
    }
}
