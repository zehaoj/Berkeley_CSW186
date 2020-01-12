package edu.berkeley.cs186.database.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import edu.berkeley.cs186.database.TransactionContext;

/**
 * LockUtil is a declarative layer which simplifies multigranularity lock acquisition
 * for the user (you, in the second half of Part 2). Generally speaking, you should use LockUtil
 * for lock acquisition instead of calling LockContext methods directly.
 */
public class LockUtil {
    /**
     * Ensure that the current transaction can perform actions requiring LOCKTYPE on LOCKCONTEXT.
     *
     * This method should promote/escalate as needed, but should only grant the least
     * permissive set of locks needed.
     *
     * lockType is guaranteed to be one of: S, X, NL.
     *
     * If the current transaction is null (i.e. there is no current transaction), this method should do nothing.
     */
    public static void ensureSufficientLockHeld(LockContext lockContext, LockType lockType) {
        // TODO(hw4_part2): implement
    	
        TransactionContext transaction = TransactionContext.getTransaction();
        if (!ifDisabled(lockContext) && lockContext.parent != null && lockContext.parent.parent != null &&
    			lockContext.parent.capacity >= 10 && lockContext.parent.saturation(transaction) >= 0.2) {
    		lockContext.parent.escalate(transaction);
    		ensureSufficientLockHeld(lockContext, lockType);
    		return;
    	}
        if (transaction == null) {
        	return;
        }
    	if (LockType.substitutable(lockContext.getEffectiveLockType(transaction), lockType)) {
    		return;
    	}
    	
        if (lockContext.parentContext() != null) {
            ensureSufficientLockHeld(lockContext.parentContext(), LockType.parentLock(lockType));
        }

        if (lockContext.getEffectiveLockType(transaction).equals(LockType.NL)) {
            lockContext.acquire(transaction, lockType);
        } else {
            try {
                lockContext.promote(transaction, lockType);
            } catch (InvalidLockException e){
            	if (lockType.equals(LockType.S)) {
                	List<ResourceName> releaseLocks = new ArrayList<>();
                	releaseLocks.add(lockContext.getResourceName());
            		for (Lock lock: lockContext.lockman.getLocks(transaction)) {
                		if (lock.name.isDescendantOf(lockContext.getResourceName()) && 
                				(!lock.lockType.toString().equals("NL") && !lock.lockType.toString().equals("X")
                						&&  
                						!lock.lockType.toString().equals("IX"))) {
                			releaseLocks.add(lock.name);
                		}
                	}
            		if (lockContext.getEffectiveLockType(transaction).equals(LockType.IX)) {
            			lockContext.lockman.acquireAndRelease(transaction, lockContext.getResourceName(), LockType.SIX, releaseLocks);
                		return;
            		}
            	}
                lockContext.escalate(transaction);
                ensureSufficientLockHeld(lockContext, lockType);
            }
        }
    }

    // TODO(hw4_part2): add helper methods as you see fit
    private static boolean ifDisabled(LockContext lc) {
    	if (lc.parent != null && lc.parent.parent != null) {
    		if (lc.parent.getLockDisable()) {
    			return true;
    		}
    	} else if (lc.parent != null && lc.parent.parent == null) {
    		if (lc.getLockDisable()) {
    			return true;
    		}
    	}
    	return false;
    } 
}



