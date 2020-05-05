# Homework 4: Locking (Part 1)

## Overview

In this part, you will implement some helpers with lock types, and the bottom two layers (the lock manager
and lock contexts).

The `concurrency` directory contains a partial implementation of a
lock manager (`LockManager`) and lock context (`LockContext`), which
you must complete in this assignment.

Small note on terminology: in this homework, "children" and "parent" refer to
the resource(s) directly below/above a resource in the hierarchy. "Descendants"
and "ancestors" are used when we wish to refer to all resource(s) below/above
in the hierarchy.

## LockType (relationship between lock types)

Before you start implementing the `LockManager`, you need to keep track of all the
lock types supported, and how they interact with each other. The `LockType` class contains
methods reasoning about this, which will come in handy in the rest of the homework.

You will need to implement the `compatible`, `canBeParentLock`, and `substitutable` methods.

`compatible(A, B)` simply checks if lock type A is compatible with lock type B - can one
transaction have lock A while another transaction has lock B on the same resource? For example,
two transactions can have S locks on the same resource, so `compatible(S, S) = true`, but
two transactions cannot have X locks on the same resource, so `compatible(X, X) = false`.

`canBeParentLock(A, B)` returns true if having A on a resource lets a transaction acquire a lock of type B on a child. For example, in order to get an
S lock on a table, we must have (at the very least) an IS lock on the parent of table: the
database. So `canBeParentLock(IS, S) = true`.

`substitutable(substitute, required)` checks if one lock type (`substitute`) can be used
in place of another (`required`). This is only the case if a transaction having `substitute`
can do everything that a transaction having `required` can do. Another way of looking at this
is: let a transaction request the required lock. Can there be any problems if we secretly
give it the substitute lock instead? For example, if a transaction requested an X lock,
and we quietly gave it an S lock, there would be problems if the transaction tries to write
to the resource. Therefore, `substitutable(S, X) = false`.

For the purposes of this homework, a transaction with:
- `S(A)` can read A and all descendants of A.
- `X(A)` can read and write A and all descendants of A.
- `IS(A)` can request shared and intent-shared locks on all children of A.
- `IX(A)` can request any lock on all children of A.
- `SIX(A)` can do anything that having `S(A)` or `IX(A)` lets it do, except requesting S or IS locks
  on children of A, which would be redundant.

## LockManager (locking independent resources)

The `LockManager` class handles locking of unrelated resources (we will add multigranularity
constraints in the next step).

**Before you start coding**, you should understand what the lock manager does, what each and every method
of the lock manager is responsible for, and how the internal state of the lock manager changes with each
operation. The different methods of the lock manager are not independent: they are like different
cogs in a machine, and trying to implement one without consideration of the others will cause you to
spend significantly more time on this homework. There is also a fair amount of logic shared between methods,
and it may be worth spending a bit of time writing some helper methods.

A simple example of a blocking acquire call is described at the bottom of this section - you should understand
it and be able to describe any other combination of calls before implementing any method.

You will need to implement the following methods of `LockManager`:
- `acquireAndRelease`: this method atomically (from the user's perspective) acquires one lock and
  releases zero or more locks. This method has priority over any queued requests (it should proceed
  even if there is a queue, and it is placed in the front of the queue if it cannot proceed).
- `acquire`: this method is the standard `acquire` method of a lock manager. It allows a transaction
  to request one lock, and grants the request if there is no queue and the request is compatible
  with existing locks. Otherwise, it should queue the request (at the back) and block the transaction.
  We do not allow implicit lock upgrades, so requesting an X lock on a resource the transaction already
  has an S lock on is invalid.
- `release`: this method is the standard `release` method of a lock manager. It allows a transaction to
  release one lock that it holds.
- `promote`: this method allows a transaction to explicitly promote/upgrade a held lock. The lock the
  transaction holds on a resource is replaced with a stronger lock on the same resource. This method has
  priority over any queued requests (it should proceed even if there is a queue, and it is placed in the
  front of the queue if it cannot proceed). We do not allow promotions to SIX, those types of requests
  should go to `acquireAndRelease`. This is because during SIX lock upgrades, it is possible we might need to
  also release redundant locks, so we need to handle these upgrades with `acquireAndRelease`.
- `getLockType`: this is the main way to query the lock manager, and returns the type of lock that
  a transaction has on a specific resource.

##### Queues
Whenever a request for a lock cannot be satisfied (either because it conflicts with locks
other transactions already have on the resource, or because there's a queue of requests for locks
on the resource and the operation does not have priority over the queue), it should be placed on the queue
(at the back, unless otherwise specified) for the resource, and the transaction making the request
should be blocked.

The queue for each resource is processed independently of other queues, and must be processed
after a lock on the resource is released, in the following manner:
- The first request (front of the queue) is considered, and if it can now be satisfied
  (i.e. it doesn't conflict with any of the currently held locks on the resource), it should
  be removed from the queue and satisfied (the transaction for that request should be given
  the lock, any locks that the request stated should be removed should be removed, and the
  transaction should be unblocked).
- The previous step should be repeated until the first request on the queue cannot be satisfied.

##### Synchronization
`LockManager`'s methods have (currently empty) `synchronized` blocks
to ensure that calls to `LockManager` are serial and that there is no interleaving of calls. You should
make sure that all accesses (both queries and modifications) to lock manager state in a method is inside *one*
synchronized block, for example:

```java
void acquire(...) {
    synchronized (this) {
        ResourceEntry entry = getResourceEntry(name); // fetch resource entry
        // do stuff
        entry.locks.add(...); // add to list of locks
    }
}
```

and not like:

```java
void acquire(...) {
    synchronized (this) {
        ResourceEntry entry = getResourceEntry(name); // fetch resource entry
    }
    // first synchronized block ended: another call to LockManager can start here
    synchronized (this) {
        // do stuff
        entry.locks.add(...); // add to list of locks
    }
}
```

or

```java
void acquire(...) {
    ResourceEntry entry = getResourceEntry(name); // fetch resource entry
    // do stuff
    // other calls can run while the above code runs, which means we could
    // be using outdated lock manager state
    synchronized (this) {
        entry.locks.add(...); // add to list of locks
    }
}
```

Transactions block the entire thread when blocked, which means that you cannot block
the transaction inside the `synchronized` block (this would prevent any other call to `LockManager`
from running until the transaction is unblocked... which is never, since the `LockManager` is the one
that unblocks the transaction).

To block a transaction, call `Transaction#prepareBlock` *inside* the synchronized block, and
then call `Transaction#block` *outside* the synchronized block. The `Transaction#prepareBlock`
needs to be in the synchronized block to avoid a race condition where the transaction may be dequeued
between the time it leaves the synchronized block and the time it actually blocks.

If tests in `TestLockManager` are timing out, double-check that you are calling
`prepareBlock` and `block` in the manner described above, and that you are not
calling `prepareBlock` without `block`. (It could also just be a regular
infinite loop, but checking that you're handling synchronization correctly is
a good place to start).

##### A simple example

Consider the following calls (this is what `testSimpleConflict` tests):
```java
Transaction t1, t2; // initialized elsewhere, T1 has transaction number 1, T2 has transaction number 2
LockManager lockman = new LockManager();
ResourceName db = new ResourceName("database");

lockman.acquire(t1, db, LockType.X); // t1 requests X(db)
lockman.acquire(t2, db, LockType.X); // t2 requests X(db)
lockman.release(t1, db); // t1 releases X(db)
```

In the first call, T1 requests an X lock on the database. There are no other locks on database,
so we grant T1 the lock. We add X(db) to the list of locks T1 has (in `transactionLocks`),
as well as to the locks held on the database (in `resourceLocks`). Our internal state now looks like:

```
transactionLocks: { 1 => [ X(db) ] } (transaction 1 has 1 lock: X(db))
resourceEntries: { db => { locks: [ {1, X(db)} ], queue: [] } }
    (there is 1 lock on db: an X lock by transaction 1, nothing on the queue)
```

In the second call, T2 requests an X lock on the database. T1 already has an X lock on database,
so T2 is not granted the lock. We add T2's request to the queue, and block T2. Our internal state
now looks like:

```
transactionLocks: { 1 => [ X(db) ] } (transaction 1 has 1 lock: X(db))
resourceEntries: { db => { locks: [ {1, X(db)} ], queue: [ LockRequest(T2, X(db)) ] } }
    (there is 1 lock on db: an X lock by transaction 1, and 1 request on queue: a request for X by transaction 2)
```

In the last call, T1 releases an X lock on the database. T2's request can now be processed,
so we remove T2 from the queue, grant it the lock by updating `transactionLocks`
and `resourceLocks`, and unblock it. Our internal state now looks like:

```
transactionLocks: { 2 => [ X(db) ] } (transaction 2 has 1 lock: X(db))
resourceEntries: { db => { locks: [ {2, X(db)} ], queue: [] } }
    (there is 1 lock on db: an X lock by transaction 2, nothing on the queue)
```

## LockContext (multigranularity constraints)

The `LockContext` class represents a single resource in the hierarchy; this is
where all multigranularity operations (such as enforcing that you have the appropriate
intent locks before acquiring or performing lock escalation) are implemented.

You will need to implement the following methods of `LockContext`:
- `acquire`: this method performs an acquire via the underlying `LockManager` after
  ensuring that all multigranularity constraints are met. For example,
  if the transaction has IS(database) and requests X(table), the appropriate
  exception must be thrown (see comments above method).
  If a transaction has a SIX lock, then it is redundant for the transactior to have an IS/S lock on any descendant resource. Therefore, in our implementation, we prohibit acquiring an IS/S lock if an ancestor has SIX, and consider this to be an invalid request.
- `release`: this method performs a release via the underlying `LockManager` after
  ensuring that all multigranularity constraints will still be met after release. For example,
  if the transaction has X(table) and attempts to release IX(database), the appropriate
  exception must be thrown (see comments above method).
- `promote`: this method performs a lock promotion via the underlying `LockManager` after
  ensuring that all multigranularity constraints are met. For example,
  if the transaction has IS(database) and requests a promotion from S(table) to X(table), the appropriate
  exception must be thrown (see comments above method).
  In the special case of promotion to SIX (from IS/IX/S), you should simultaneously release all descendant locks of type S/IS, since we disallow having IS/S locks on descendants when a SIX lock is held. You should also disallow promotion to a SIX lock if an ancestor has SIX, because this would be redundant.

  *Note*: this does still allow for SIX locks to be held under a SIX lock, in
  the case of promoting an ancestor to SIX while a descendant holds SIX. This is
  redundant, but fixing it is both messy (have to swap all descendant SIX locks
  with IX locks) and pointless (you still hold a lock on the descendant
  anyways), so we just leave it as is.

- `escalate`: this method performs lock escalation up to the current level (see below for more
  details). Since interleaving of multiple `LockManager` calls by multiple transactions (running
  on different threads) is allowed, you must make sure to only use one mutating call to the
  `LockManager` and only request information about the current transaction from the `LockManager`
  (since information pertaining to any other transaction may change between the querying and
  the acquiring).
- `getExplicitLockType`: this method returns the type of the lock explicitly held at the current level. For example,
  if a transaction has X(db), `dbContext.getExplicitLockType(transaction)` should return X, but
  `tableContext.getExplicitLockType(transaction)` should return NL (no lock explicitly held).
- `getEfectiveLockType`: this method returns the type of the lock either implicitly or explicitly held at the current level. For example,
  if a transaction has X(db), `dbContext.getEfectiveLockType(transaction)` should return X, and
  `tableContext.getEfectiveLockType(transaction)` should *also* return X (since we implicitly have an X lock on every
  table due to explicitly having an X lock on the entire database). Note that since an intent lock does *not*
  implicitly grant lock-acquiring privileges to lower levels, if a transaction only has SIX(database),
  `tableContext.getEfectiveLockType(transaction)` should return S (not SIX), since the transaction
  implicitly has S on table via the SIX lock, but not the IX part of the SIX lock (which is only available at the
  database level). Note that it is possible for the explicit lock type to be one type, and the effective lock type
  to be a different lock type, specifically if there is an ancestor that has a SIX lock.

##### Hierarchy

The `LockContext` objects all share a single underlying `LockManager` object, which was
implemented in the previous step. The `parentContext` method returns the parent of the
current context (e.g. the lock context of the database is returned when `tableContext.parentContext()`
is called), and the `childContext` method returns the child lock context with the name passed in
(e.g. `tableContext.childContext(0L)` returns the context of page 0 of the table). There is
exactly one `LockContext` for each resource: calling `childContext` with the same parameters
multiple times returns the same object.

The provided code already initializes this tree of lock contexts for you. For performance reasons,
however, we do not create lock contexts for every page of a table immediately. Instead, we create them
as the corresponding `Page` objects are created. This means that the capacity of the lock context
(which we define to be the total number of children of the lock context) is not necessarily
the number of `LockContext`s we created via `childContext`.

##### Lock Escalation

Lock escalation is the process of going from many fine locks (locks at lower levels in the hierarchy)
to a single coarser lock (lock at a higher level). For example, we can escalate many page locks
that a transaction holds into a single lock at the table level.

We perform lock escalation through `LockContext#escalate`. A call to this method should be interpreted
as a request to escalate all locks on descendants (these are the fine locks) into one lock on the
context that `escalate` was called with (the coarse lock). The fine locks may be any mix of intent
and regular locks, but we limit the coarse lock to be either S or X.

For example, if we have the following locks: IX(database), SIX(table), X(page 1), X(page 2), X(page 4),
and call `tableContext.escalate(transaction)`, we should replace the page-level locks
with a single lock on the table that encompasses them:

![Diagram of before/after escalate, ending with IX(database), X(table)](images/hw4-escalate1.png)

Likewise, if we called `dbContext.escalate(transaction)`, we should replace the page-level locks  and
table-level locks with a single lock on the database that encompasses them:

![Diagram of before/after escalate, ending with X(database)](images/hw4-escalate2.png)

Note that escalating to an X lock always "works" in this regard: having a coarse X lock definitely
encompasses having a bunch of finer locks. However, this introduces other complications: if the transaction
previously held only finer S locks, it would not have the IX locks required to hold an X lock, and escalating to an
X reduces the amount of concurrency allowed unnecessarily. We therefore require that `escalate` only escalate
to the least permissive lock type (out of {S, X}) that still encompasses the replaced finer locks
(so if we only had IS/S locks, we should escalate to S, not X).

Also note that since we are only escalating to S or X, a transaction that only has IS(database) would
escalate to S(database). Though a transaction that only has IS(database) technically has no locks
at lower levels, the only point in keeping an intent lock at this level would be to acquire a normal
lock at a lower level, and the point in escalating is to avoid having locks at a lower level. Therefore,
we don't allow escalating to intent locks (IS/IX/SIX).

## Additional Notes

After this, you should pass all the tests we have provided to you in `database.concurrency.TestLockType`,
`database.concurrency.TestLockManager`, and `database.concurrency.TestLockContext`.

Note that you may **not** modify the signature of any methods or classes that we
provide to you, but you're free to add helper methods. Also, you should only modify code in
the `concurrency` directory for this part.
