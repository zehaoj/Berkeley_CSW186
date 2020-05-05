# Homework 4: Locking

This homework is divided into two parts.

Part 1 is due: **Thursday, 11/7/2019, 11:59 PM**.

Part 2 is due: **Saturday, 11/16/2019, 11:59 PM**.

Part 2 requires Part 1 to be completed first. See the Grading section at the
bottom of this document for notes on how your score will be computed.

**You will not be able to use slip minutes for the Part 1 deadline.** The standard late penalty
(33%/day, counted in days not minutes) will apply after the Part 1 deadline. Slip minutes
may be used for the Part 2 deadline, and the late penalty for the two parts are independent.

For example, if you submit Part 1 at 5:30 AM two days after it is due,
and Part 2 at 6:00 PM the day after it is due, you will recieve:
- 66% penalty on your Part 1 submission
- No penalty on your Part 2 submission
- A total of 1080 slip minutes consumed

## Overview

In this assignment, you will implement multigranularity locking and integrate
it into the codebase.

## Prerequisites

You should watch both the Transactions and Concurrency Part 1 and Transactions
and Concurrency Part 2 lectures before working on this homework.

## Getting Started

The test cases for Part 1 are located in
`src/test/java/edu/berkeley/cs186/database/concurrency` (in particular,
`TestLockType`, `TestLockManager`, `TestLockContext`).

The test cases for Part 2 are located in
`src/test/java/edu/berkeley/cs186/database/concurrency/TestLockUtil.java`,
`src/test/java/edu/berkeley/cs186/database/TestDatabaseDeadlockPrecheck.java`, and
`src/test/java/edu/berkeley/cs186/database/TestDatabaseLocking.java`.

To build and test your code in the container, run:
```bash
mvn clean test -D HW=4
```

There should be 70 failures, 1 error, and 76 tests run.

To run just Part 1 tests, run:
```bash
mvn clean test -D HW=4Part1
```

There should be 40 failures and 42 tests run.

To run just Part 2 tests, run:
```bash
mvn clean test -D HW=4Part2
```

There should be 30 failures, 1 error, and 34 tests run. (These numbers may
change as you work through Part 1).

## Part 0: Understand the Skeleton Code

Read through all of the code in the `concurrency` directory (including the classes that you are not touching -
they may contain useful methods or information pertinent to the homework). Many comments contain
critical information on how you must implement certain functions. If you do not obey
the comments, you will lose points.

Try to understand how each class fits in: what is each class responsible for, what are
all the methods you have to implement, and how does each one manipulate the internal state.
Trying to code one method at a time without understanding how all the parts of the lock
manager work often results in having to rewrite significant amounts of code.

The skeleton code divides multigranularity locking into three layers.
- The `LockManager` object manages all the locks, treating each resource as independent
  (it doesn't consider the relationship between two resources: it does not understand that
  a table is at a lower level in the hierarchy than the database, and just treats locks on
  both in exactly the same way). This level is responsible for blocking/unblocking transactions
  as necessary, and is the single source of authority on whether a transaction has a certain lock. If
  the `LockManager` says T1 has X(database), then T1 has X(database).
- A collection of `LockContext` objects, which each represent a single lockable object
  (e.g. a page or a table) lies on top of the `LockManager`. The `LockContext` objects
  are connected according to the hierarchy (e.g. a `LockContext` for a table has the database
  context as its parent, and its pages' contexts as children). The `LockContext` objects
  all share a single `LockManager`, and enforces multigranularity constraints on all
  lock requests (e.g. an exception should be thrown if a transaction attempts to request
  X(table) without IX(database)).
- A declarative layer lies on top of the collection of `LockContext` objects, and is responsible
  for acquiring all the intent locks needed for each S or X request that the database uses
  (e.g. if S(page) is requested, this layer would be responsible for requesting IS(database), IS(table)
  if necessary).

![Layers of locking in HW4](images/hw4-layers.png?raw=true "Layers")

In Part 1, you will be implementing the bottom two layers (`LockManager` and `LockContext`).

In Part 2, you will be implementing the top layer (`LockUtil`) and integrating everything into the codebase.

## Part 1: LockManager and LockContext

[Part 1 README](hw4-part1-README.md)

## Part 2: LockUtil and integration

[Part 2 README](hw4-part2-README.md)

## Submitting the Assignment

See Piazza for submission instructions.

You may **not** modify the signature of any methods or classes that we
provide to you, but you're free to add helper methods.

You should make sure that all code you modify belongs to files with HW4 todo comments in them
(e.g. don't add helper methods to PNLJOperator). A full list of files that you may modify follows:

- concurrency/LockType.java
- concurrency/LockManager.java
- concurrency/LockContext.java
- concurrency/LockUtil.java (Part 2 only)
- index/BPlusTree.java (Part 2 only)
- memory/Page.java (Part 2 only)
- table/PageDirectory.java (Part 2 only)
- table/Table.java (Part 2 only)
- Database.java (Part 2 only)

Make sure that your code does *not* use any static (non-final) variables - this may cause odd behavior when
running with maven vs. in your IDE (tests run through the IDE often run with a new instance
of Java for each test, so the static variables get reset, but multiple tests per Java instance
may be run when using maven, where static variables *do not* get reset).

## Testing

We strongly encourage testing your code yourself, especially after each part (rather than all at the end). The given
tests for this project (even more so than previous projects) are **not** comprehensive tests: it **is** possible to write
incorrect code that passes them all.

Things that you might consider testing for include: anything that we specify in the comments or in this document that a method should do
that you don't see a test already testing for, and any edge cases that you can think of. Think of what valid inputs
might break your code and cause it not to perform as intended, and add a test to make sure things are working.

To help you get started, here is one case that is **not** in the given tests (and will be included in the hidden tests):
if a transaction holds IX(database), IS(table), S(page) and promotes the
database lock to a SIX lock via `LockContext#promote`, `numChildLocks` should
be updated to be 0 for both the database and table contexts.

To add a unit test, open up the appropriate test file (all test files are located in `src/test/java/edu/berkeley/cs186/database`
or subdirectories of it), and simply add a new method to the test class, for example:

```
@Test
public void testPromoteSIXSaturation() {
    // your test code here
}
```

Many test classes have some setup code done for you already: take a look at other tests in the file for an idea of how to write the test code.

## Grading
- **20% of your overall score will come from your submission for Part 1**. We will only be running released Part 1 tests (`database.concurrency.TestLockType`, `database.concurrency.TestLockManager`, `database.concurrency.TestLockContext`) on your Part 1 submission.
- The rest of your score will come from your submission for Part 2 (testing both Part 1 and Part 2 code).
- 50% of your overall score will be made up of the tests released in this homework (the tests that we provided
  in `database.concurrency.*`, `database.TestDatabaseDeadlockPrecheck`, and `database.TestDatabaseLocking`).
- 50% of your overall score will be made up of hidden, unreleased tests that we will run on your submission after the deadline.
- Part 1 is worth 60% of your HW4 grade and Part 2 is worth 40% of your HW4 grade.

For example, if:
- your part 1 submission passes 44% of the part 1 public tests, and
- your part 2 submission passes
    - 79% of the part 1 public tests,
    - 65% of the part 1 hidden tests,
    - 55% of the part 2 public tests, and
    - 43% of the part 2 hidden tests,

then,
- your score for part 1 public tests (30% of grade) would be
`(2 * 0.44 + 0.79) / 3.00 = 0.5567` (20% overall from part 1 submission)
- your score for part 1 hidden tests (30% of grade) would be `0.65/1.00`,
- your score for part 2 public tests (20% of grade) would be `0.55/1.00`,
- your score for part 2 hidden tests (20% of grade) would be `0.43/1.00`,

and your overall score would be:
`(0.30 * 0.5567 + 0.30 * 0.65 + 0.20 * 0.55 + 0.20 * 0.43)`
or about 55.8%.

