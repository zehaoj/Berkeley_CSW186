package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.Pair;

import java.util.*;

/**
 * LockManager maintains the bookkeeping for what transactions have
 * what locks on what resources. The lock manager should generally **not**
 * be used directly: instead, code should call methods of LockContext to
 * acquire/release/promote/escalate locks.
 *
 * The LockManager is primarily concerned with the mappings between
 * transactions, resources, and locks, and does not concern itself with
 * multiple levels of granularity (you can and should treat ResourceName
 * as a generic Object, rather than as an object encapsulating levels of
 * granularity, in this class).
 *
 * It follows that LockManager should allow **all**
 * requests that are valid from the perspective of treating every resource
 * as independent objects, even if they would be invalid from a
 * multigranularity locking perspective. For example, if LockManager#acquire
 * is called asking for an X lock on Table A, and the transaction has no
 * locks at the time, the request is considered valid (because the only problem
 * with such a request would be that the transaction does not have the appropriate
 * intent locks, but that is a multigranularity concern).
 *
 * Each resource the lock manager manages has its own queue of LockRequest objects
 * representing a request to acquire (or promote/acquire-and-release) a lock that
 * could not be satisfied at the time. This queue should be processed every time
 * a lock on that resource gets released, starting from the first request, and going
 * in order until a request cannot be satisfied. Requests taken off the queue should
 * be treated as if that transaction had made the request right after the resource was
 * released in absence of a queue (i.e. removing a request by T1 to acquire X(db) should
 * be treated as if T1 had just requested X(db) and there were no queue on db: T1 should
 * be given the X lock on db, and put in an unblocked state via Transaction#unblock).
 *
 * This does mean that in the case of:
 *    queue: S(A) X(A) S(A)
 * only the first request should be removed from the queue when the queue is processed.
 */
public class LockManager {
    // transactionLocks is a mapping from transaction number to a list of lock
    // objects held by that transaction.
    private Map<Long, List<Lock>> transactionLocks = new HashMap<>();
    // resourceEntries is a mapping from resource names to a ResourceEntry
    // object, which contains a list of Locks on the object, as well as a
    // queue for requests on that resource.
    private Map<ResourceName, ResourceEntry> resourceEntries = new HashMap<>();

    // A ResourceEntry contains the list of locks on a resource, as well as
    // the queue for requests for locks on the resource.
    private class ResourceEntry {
        // List of currently granted locks on the resource.
        List<Lock> locks = new ArrayList<>();
        // Queue for yet-to-be-satisfied lock requests on this resource.
        Deque<LockRequest> waitingQueue = new ArrayDeque<>();

        // TODO(hw4_part1): You may add helper methods here if you wish
        public boolean isLocked() {
        	if (locks.size() == 0) {
        		return false;
        	} else {
        		return true;
        	}
        }
        
        public boolean isWaiting() {
        	if (waitingQueue.isEmpty()) {
        		return false;
        	} else {
        		return true;
        	}
        }
        
        public void addFirstWait(LockRequest lr) {
        	waitingQueue.addFirst(lr);
        }
        
        public void addLock(Lock lock) {
        	locks.add(lock);
        }
        
        public void addWaiting(LockRequest lr) {
        	waitingQueue.add(lr);
        }
        
        public void removeLock(Lock lock) {
        	locks.remove(lock);
        }
        
        public void moveQueuetoLock() {
        	locks.add(waitingQueue.getFirst().lock);
//        	locks.add(waitingQueue.poll().lock);
        	waitingQueue.removeFirst();
        }
        
        public LockRequest getFirstWait() {
        	return waitingQueue.getFirst();
        }
        
        public boolean allCompatible(Lock givenLock) {
        	for (Lock lock: locks) {
        		if ((lock.transactionNum != givenLock.transactionNum) && 
        				(!LockType.compatible(lock.lockType, givenLock.lockType))) {
        			return false;
        		}
        	}
        	return true;
        }
        
        @Override
        public String toString() {
            return "Active Locks: " + Arrays.toString(this.locks.toArray()) +
                   ", Queue: " + Arrays.toString(this.waitingQueue.toArray());
        }
    }

    // You should not modify or use this directly.
    private Map<Long, LockContext> contexts = new HashMap<>();

    /**
     * Helper method to fetch the resourceEntry corresponding to NAME.
     * Inserts a new (empty) resourceEntry into the map if no entry exists yet.
     */
    private ResourceEntry getResourceEntry(ResourceName name) {
        resourceEntries.putIfAbsent(name, new ResourceEntry());
        return resourceEntries.get(name);
    }

    // TODO(hw4_part1): You may add helper methods here if you wish

    /**
     * Acquire a LOCKTYPE lock on NAME, for transaction TRANSACTION, and releases all locks
     * in RELEASELOCKS after acquiring the lock, in one atomic action.
     *
     * Error checking must be done before any locks are acquired or released. If the new lock
     * is not compatible with another transaction's lock on the resource, the transaction is
     * blocked and the request is placed at the **front** of ITEM's queue.
     *
     * Locks in RELEASELOCKS should be released only after the requested lock has been acquired.
     * The corresponding queues should be processed.
     *
     * An acquire-and-release that releases an old lock on NAME **should not** change the
     * acquisition time of the lock on NAME, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), acquire X(A) and release S(A), the
     * lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if a lock on NAME is held by TRANSACTION and
     * isn't being released
     * @throws NoLockHeldException if no lock on a name in RELEASELOCKS is held by TRANSACTION
     */
    public void acquireAndRelease(TransactionContext transaction, ResourceName name,
                                  LockType lockType, List<ResourceName> releaseLocks)
    throws DuplicateLockRequestException, NoLockHeldException {
        // TODO(hw4_part1): implement
        // You may modify any part of this method. You are not required to keep all your
        // code within the given synchronized block -- in fact,
        // you will have to write some code outside the synchronized block to avoid locking up
        // the entire lock manager when a transaction is blocked. You are also allowed to
        // move the synchronized block elsewhere if you wish.
    	boolean blocked = false;
    	if (this.getLockType(transaction, name).toString().equals(lockType.toString())) {
    		throw new DuplicateLockRequestException("Duplicate lock request!");
    	}
        synchronized (this) {
        	ResourceEntry entry = getResourceEntry(name);
        	if (!entry.isLocked() || (entry.allCompatible(new Lock(name, lockType, transaction.getTransNum())) && !entry.isWaiting())) {
        		entry.addLock(new Lock(name, lockType, transaction.getTransNum()));
        		List<Lock> transList = new ArrayList<>();
        		if (this.transactionLocks.get(transaction.getTransNum()) != null) {
        			transList = this.transactionLocks.get(transaction.getTransNum());
            		transList.add(new Lock(name, lockType, transaction.getTransNum()));
        			this.transactionLocks.put(transaction.getTransNum(), transList);
        			
        		} else {
            		transList.add(new Lock(name, lockType, transaction.getTransNum()));
        			this.transactionLocks.put(transaction.getTransNum(), transList);
        		}
        		for (ResourceName rn: releaseLocks) {
        	    	if (this.getLockType(transaction, rn).toString().equals("NL")) {
        	    		throw new NoLockHeldException("No lock held!");
        	    	}
        	    	LockType lt = this.getLockType(transaction, rn);
                	ResourceEntry entry1 = getResourceEntry(rn);
            		List<Lock> transList1 = new ArrayList<>();
            		transList1 = this.transactionLocks.get(transaction.getTransNum());
            		transList1.remove(new Lock(rn, lt, transaction.getTransNum()));
            		transactionLocks.remove(transaction.getTransNum());
            		transactionLocks.put(transaction.getTransNum(), transList1);
                	entry1.locks.remove(new Lock(rn, lt, transaction.getTransNum()));


                	if (entry1.isWaiting()) {

                		LockRequest lr = entry1.getFirstWait();
                		boolean satis = this.isValid(lr, rn);
                		while (satis) {
//                			System.out.println("here");
                			lr.transaction.unblock();

        	        		entry1.moveQueuetoLock();
        	        		transList1 = new ArrayList<>();
        	        		
        	        		List<Lock> tmptransList = transactionLocks.get(lr.transaction.getTransNum());
        	        		if (tmptransList != null) {
//        	        			
        	        			for (Lock translock: tmptransList) {
        	        				if (translock.transactionNum != lr.lock.transactionNum || 
        	        						translock.name != lr.lock.name) {
        	        					transList1.add(translock);
        	        				}
        	        			}
        	        			
        	        			transList1.add(new Lock(rn, lr.lock.lockType, lr.transaction.getTransNum()));
        	            	} else {
        	            		transList1.add(new Lock(rn, lr.lock.lockType, lr.transaction.getTransNum()));
        	        		}
        	        		transactionLocks.remove(lr.transaction.getTransNum());
        	    			transactionLocks.put(lr.transaction.getTransNum(), transList1);
        	    			if (!entry1.isWaiting())
        	    				break;
        	    			
        	    			lr = entry1.getFirstWait();
        	        		satis = this.isValid(lr, rn);
                		}
                	}
        		}
        	} else {
        		entry.addWaiting(new LockRequest(transaction, 
        				new Lock(name, lockType, transaction.getTransNum())));
        		transaction.prepareBlock();
        		blocked = true;
        		
        	}
        }
        if (blocked) {
        	transaction.block();
        }
    }

    /**
     * Acquire a LOCKTYPE lock on NAME, for transaction TRANSACTION.
     *
     * Error checking must be done before the lock is acquired. If the new lock
     * is not compatible with another transaction's lock on the resource, or if there are
     * other transaction in queue for the resource, the transaction is
     * blocked and the request is placed at the **back** of NAME's queue.
     *
     * @throws DuplicateLockRequestException if a lock on NAME is held by
     * TRANSACTION
     */
    public void acquire(TransactionContext transaction, ResourceName name,
                        LockType lockType) throws DuplicateLockRequestException {
        // TODO(hw4_part1): implement
        // You may modify any part of this method. You are not required to keep all your
        // code within the given synchronized block -- in fact,
        // you will have to write some code outside the synchronized block to avoid locking up
        // the entire lock manager when a transaction is blocked. You are also allowed to
        // move the synchronized block elsewhere if you wish.
    	if (!this.getLockType(transaction, name).toString().equals("NL")) {
    		throw new DuplicateLockRequestException("Duplicate lock request!");
    	}
    	boolean blocked = false;
    	synchronized (this) {
        	ResourceEntry entry = getResourceEntry(name);
        	if (!entry.isLocked() || (entry.allCompatible(new Lock(name, lockType, transaction.getTransNum())) && !entry.isWaiting())) {
        		entry.addLock(new Lock(name, lockType, transaction.getTransNum()));
        		List<Lock> transList = new ArrayList<>();
        		if (this.transactionLocks.get(transaction.getTransNum()) != null) {
        			transList = this.transactionLocks.get(transaction.getTransNum());
            		transList.add(new Lock(name, lockType, transaction.getTransNum()));
        			this.transactionLocks.remove(transaction.getTransNum());
        			this.transactionLocks.put(transaction.getTransNum(), transList);
        		} else {
            		transList.add(new Lock(name, lockType, transaction.getTransNum()));
        			this.transactionLocks.put(transaction.getTransNum(), transList);
        		}
        	} else {
        		entry.addWaiting(new LockRequest(transaction, 
        				new Lock(name, lockType, transaction.getTransNum())));
        		transaction.prepareBlock();
        		blocked = true;
        	}
        }
        if (blocked) {
        	transaction.block();
        }
    }

    /**
     * Release TRANSACTION's lock on NAME.
     *
     * Error checking must be done before the lock is released.
     *
     * NAME's queue should be processed after this call. If any requests in
     * the queue have locks to be released, those should be released, and the
     * corresponding queues also processed.
     *
     * @throws NoLockHeldException if no lock on NAME is held by TRANSACTION
     */
    public void release(TransactionContext transaction, ResourceName name)
    throws NoLockHeldException {
        // TODO(hw4_part1): implement
        // You may modify any part of this method.
        synchronized (this) {
        	if (this.getLockType(transaction, name).toString().equals("NL")) {
        		throw new NoLockHeldException("No lock held!");
        	}
        	LockType lt = this.getLockType(transaction, name);
        	ResourceEntry entry = getResourceEntry(name);
    		List<Lock> transList = new ArrayList<>();
    		transList = this.transactionLocks.get(transaction.getTransNum());
    		transList.remove(new Lock(name, lt, transaction.getTransNum()));
    		transactionLocks.remove(transaction.getTransNum());
    		transactionLocks.put(transaction.getTransNum(), transList);
        	entry.removeLock((new Lock(name, lt, transaction.getTransNum())));

        	if (entry.isWaiting()) {
        		LockRequest lr = entry.getFirstWait();
        		boolean satis = this.isValid(lr, name);
        		while (satis) {
        			lr.transaction.unblock();

	        		entry.moveQueuetoLock();
	        		transList = new ArrayList<>();
	        		
	        		List<Lock> tmptransList = transactionLocks.get(lr.transaction.getTransNum());
	        		if (tmptransList != null) {
	        			for (Lock translock: tmptransList) {
	        				if (translock.transactionNum != lr.lock.transactionNum || 
	        						translock.name != lr.lock.name) {
	        					transList.add(translock);
	        				}
	        			}
	        			transList.add(new Lock(name, lr.lock.lockType, lr.transaction.getTransNum()));
	            	} else {
	            		transList.add(new Lock(name, lr.lock.lockType, lr.transaction.getTransNum()));
	        		}
	        		transactionLocks.remove(lr.transaction.getTransNum());
	    			transactionLocks.put(lr.transaction.getTransNum(), transList);
	    			if (!entry.isWaiting())
	    				break;
	    			
	    			lr = entry.getFirstWait();
	        		satis = this.isValid(lr, name);
        		}
        	}
        	
        }
    }
    
    
    
    private synchronized boolean isValid(LockRequest lr, ResourceName rn) {
    	Lock lock = lr.lock;
    	for (Lock l: this.getLocks(rn)) {
    		if (!LockType.compatible(l.lockType, lock.lockType) &&
    				l.transactionNum != lock.transactionNum) {
    			return false;
    		}
    	}
    	return true;
    }

    /**
     * Promote TRANSACTION's lock on NAME to NEWLOCKTYPE (i.e. change TRANSACTION's lock
     * on NAME from the current lock type to NEWLOCKTYPE, which must be strictly more
     * permissive).
     *
     * Error checking must be done before any locks are changed. If the new lock
     * is not compatible with another transaction's lock on the resource, the transaction is
     * blocked and the request is placed at the **front** of ITEM's queue.
     *
     * A lock promotion **should not** change the acquisition time of the lock, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), promote X(A), the
     * lock on A is considered to have been acquired before the lock on B.
     *
     * newLockType should not be LockType.SIX, this should be handled by acquireAndRelease
     *
     * @throws DuplicateLockRequestException if TRANSACTION already has a
     * NEWLOCKTYPE lock on NAME
     * @throws NoLockHeldException if TRANSACTION has no lock on NAME
     * @throws InvalidLockException if the requested lock type is not a promotion. A promotion
     * from lock type A to lock type B is valid if and only if B is substitutable
     * for A, and B is not equal to A. Also throws this exception if newLockType is LockType.SIX
     */
    public void promote(TransactionContext transaction, ResourceName name,
                        LockType newLockType)
    throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(hw4_part1): implement
        // You may modify any part of this method.
        synchronized (this) {
        	LockType lt = this.getLockType(transaction, name);
        	if (lt.toString().equals("NL")) {
        		throw new NoLockHeldException("No lock held!");
        	}
        	if (lt.equals(newLockType)) {
        		throw new DuplicateLockRequestException("Duplicate lock request!");
        	}
        	if (!LockType.substitutable(newLockType, lt) ) {
        		throw new InvalidLockException("Not a promotion!");
        	}
        	
        	ResourceEntry entry = getResourceEntry(name);
        	if (entry.allCompatible(new Lock(name, newLockType, transaction.getTransNum()))) {
        		release(transaction, name);
        		acquire(transaction, name, newLockType);
        	} else {
        		entry.addFirstWait(new LockRequest(transaction, 
        				new Lock(name, newLockType, transaction.getTransNum())));
        	}
        	
        }
    }

    /**
     * Return the type of lock TRANSACTION has on NAME (return NL if no lock is held).
     */
    public synchronized LockType getLockType(TransactionContext transaction, ResourceName name) {
    	List<Lock> nameList = this.getLocks(name);
    	List<Lock> transList = this.getLocks(transaction);
    	for (Lock lock1: nameList) {
    		if (transList.contains(lock1)) {
    			return lock1.lockType;
    		}
    	}
		return LockType.NL;
    }

    /**
     * Returns the list of locks held on NAME, in order of acquisition.
     * A promotion or acquire-and-release should count as acquired
     * at the original time.
     */
    public synchronized List<Lock> getLocks(ResourceName name) {
        return new ArrayList<>(resourceEntries.getOrDefault(name, new ResourceEntry()).locks);
    }

    /**
     * Returns the list of locks locks held by
     * TRANSACTION, in order of acquisition. A promotion or
     * acquire-and-release should count as acquired at the original time.
     */
    public synchronized List<Lock> getLocks(TransactionContext transaction) {
        return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),
                               Collections.emptyList()));
    }

    /**
     * Creates a lock context. See comments at
     * he top of this file and the top of LockContext.java for more information.
     */
    public synchronized LockContext context(String readable, long name) {
        if (!contexts.containsKey(name)) {
            contexts.put(name, new LockContext(this, null, new Pair<>(readable, name)));
        }
        return contexts.get(name);
    }

    /**
     * Create a lock context for the database. See comments at
     * the top of this file and the top of LockContext.java for more information.
     */
    public synchronized LockContext databaseContext() {
        return context("database", 0L);
    }
}
