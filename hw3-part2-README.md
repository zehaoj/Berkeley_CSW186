# Homework 3: Joins and Query Optimization (Part 2)

## Overview

In this part, you will implement two pieces of a relational query optimizer: (1)
cost estimation, and (2) plan space search.

## Cost Estimation and Maintenance of Statistics

The most important part of a query optimizer is arguably its cost estimator -- it doesn't
matter how efficiently you search the plan space or how large your plan space is
if you can't even compare two plans!

To estimate the I/O costs for a query operator, we need some statistics about
the inputs. To this end, we maintain histograms of data in each table via
`TableStats` objects. These objects can be queried for a given query operator,
via the `QueryOperator#getStats` object.

**NOTE:** these statistics are meant to be approximate, so please pay careful attention to how we define the quantities to track.

A histogram maintains approximate statistics about a (potentially large) set of
values without explicitly storing the values (which might not fit in memory). We
store our histograms as an ordered list of "buckets". Each bucket has a low and
high value, and represents the range \[low, high). The only exception to this is
that, **for the last bucket, the upper bound is inclusive** (e.g. the very last
bucket represents the range \[low, high] instead of \[low, high)). Each bucket
counts the total number of values, as well as the number of distinct values,
that fall into its range:

```java
Bucket<Float> b = new Bucket(10.0, 100.0); //defines a bucket whose low value is 10 and high is 100
b.getStart(); //returns 10.0
b.getEnd(); //returns 100.0
b.increment(15);// adds the value 15 to the bucket
b.getCount();//returns the number of items added to the bucket
b.getDistinctCount();//returns the approximate number of distinct iterms added to the bucket
```

We represent a single histogram with the `Histogram` class, which assumes
floating point values. In order to allow for other data types, we define
a "quantization" method (`Histogram#quantization`), which converts other data
types into floats for use with the histogram.

The `Histogram#buildHistograms` method takes in a table and an attribute and
initializes the buckets and sets them to an initial count based on the data in
the table. You will need to use the appropriate `Bucket` methods to do this --
see the comments inside the method.

The `Histogram#filter` method takes in a predicate and returns new buckets for
the distribution of data resulting from applying the predicate onto the data. It
calls one of several methods depending on the predicate (including
`allEquality`, `allNotEquality`, `allGreaterThan`, and `allLessThan`).

You will need to implement the following methods:

- `Histogram#buildHistogram`
- `Histogram#allEquality`
- `Histogram#allNotEquality`
- `Histogram#allGreaterThan`
- `Histogram#allLessThan`

Once you have implemented all five methods, all the tests in `TestHistogram`
should pass.

## Plan Space Search

Now that you have working cost estimates, you can now search the plan space.
For our database, this is similar to System R: the set of all left-deep trees,
avoiding cartesian products where possible. Unlike System R, we do not consider
interesting orders, and further, we completely disallow cartesian products in
all queries. To search the plan space, we will utilize the dynamic programming
algorithm used in the Selinger optimizer.

Before you begin, you should have a good idea of how the `QueryPlan` class is
used (see the [HW3 README](hw3-README.md#query)) and how query operators fit together.
For example, to implement a simple query with a single selection predicate:
```java
/**
 * SELECT * FROM myTableName WHERE stringAttr = 'CS 186'
 */
QueryOperator source = SequentialScanOperator(transaction, myTableName);
QueryOperator select = SelectOperator(source, 'stringAttr', PredicateOperator.EQUALS, "CS 186");

int estimatedIOCost = select.estimateIOCost(); // estimate I/O cost
Iterator<Record> iter = select.iterator(); // iterator over the results
```

A tree of `QueryOperator` objects is formed when we have multiple tables joined
together. The current implementation of `QueryPlan#execute`, which is called by
the user to run the query, is to join all tables in the order given by the user:
if the user says `SELECT * FROM t1 JOIN t2 ON .. JOIN t3 ON ..`, then it scans
`t1`, then joins `t2`, then joins `t3`. This will perform poorly in many cases,
so your task is to implement the dynamic programming algorithm to join the
tables together in a better order.

You will have to implement the `QueryPlan#execute` method. To do so, you will
also have to implement two helper methods: `QueryPlan#minCostSingleAccess` (pass
1 of the dynamic programming algorithm) and `QueryPlan#minCostJoins` (pass
i > 1).

#### Single Table Access Selection (Pass 1)

Recall that the first part of the search algorithm involves finding the lowest
estimated cost plans for accessing each table individually (pass i involves
finding the best plans for sets of i tables, so pass 1 involves finding the best
plans for sets of 1 table).

This functionality should be implemented in the `QueryPlan#minCostSingleAccess`
helper method, which takes a table and returns the optimal `QueryOperator` for
scanning the table.

In our database, we only consider two types of table scans: a sequential, full
table scan (`SequentialScanOperator`) and an index scan (`IndexScanOperator`),
which requires an index and filtering predicate on a column.

You should first calculate the estimated I/O cost of a sequential scan, since
this is always possible (it's the default option: we only move away from it in
favor of index scans if the index scan is both possible and more efficient).

Then, if there are any indices on any column of the table that we have
a selection predicate on, you should calculate the estimated I/O cost of doing
an index scan on that column. If any of these are more efficient than the
sequential scan, take the best one.

Finally, as part of a heuristic-based optimization covered in class, you should
push down any selection predicates that involve solely the table (see
`QueryPlan#addEligibleSelections`).

This should leave you with a query operator beginning with a sequential or index
scan operator, followed by zero or more `SelectOperator`s.

After you have implemented `QueryPlan#minCostSingleAccess`, you should be
passing all of the tests in `TestSingleAccess`. These tests do not involve any
joins.

#### Join Selection (Pass i > 1)

Recall that for i > 1, pass i of the dynamic programming algorithm takes in
optimal plans for joining together all possible sets of i - 1 tables (except those
involving cartesian products), and returns optimal plans for joining together all
possible sets of i tables (again excluding those with cartesian products).

We represent the state between two passes as a mapping from sets of strings
(table names) to the corresponding optimal `QueryOperator`. You will need to
implement the logic for pass i (i > 1) of the search algorithm in the
`QueryPlan#minCostJoins` helper method.

This method should, given a mapping from sets of i - 1 tables to the optimal
plan for joining together those i - 1 tables, return a mapping from sets of
i tables to the optimal left-deep plan for joining all sets of i tables
(except those with cartesian products).

You should use the list of explicit join conditions added when the user calls
the `QueryPlan#join` method to identify potential joins.

There are no public test cases for this method, so you will need to implement
`execute` (the next section) before any more tests pass.

**Note:** you should not add any selection predicates in this method. This is
because in our database, we only allow two column predicates in the join
condition, and a conjunction of single column predicates otherwise, so the only
unprocessed selection predicates in pass i > 1 are the join conditions. *This is
not generally the case!* SQL queries can contain selection predicates that can
*not* be processed until multiple tables have been joined together, for
example:

```sql
SELECT * FROM t1, t2, t3, t4 WHERE (t1.a = t2.b OR t2.b = t2.c)
```

where the single predicate cannot be evaluated until after `t1`, `t2`, _and_
`t3` have been joined together. Therefore, a database that supports all of SQL
would have to push down predicates after each pass of the search algorithm.

#### Optimal Plan Selection

Your final task is to write the outermost driver method of the optimizer,
`QueryPlan#execute`, which should utilize the two helper methods you have implemented
to find the best query plan.

You will need to add the remaining group by and projection operators that are
a part of the query, but have not yet been added to the query plan (see the
private helper methods implemented for you in the `QueryPlan` class).

**Note:** The tables in `QueryPlan` are defined as 1 `startTableName` and some `joinTableNames`. 
The `startTableName` doesn't have to be the table to start joining with, it's just to line up the 
indices in `joinTableNames`, `joinLeftColumnNames`, and `joinRightColumnNames`, because joining n tables 
requires n-1 joins. So be sure to include the `startTableName` and all of the `joinTableNames` in your `QueryPlan#execute`.

## Additional Notes

After this, you should pass all the tests we have provided to you in `database.table.stats.*` and `database.query.*`.

Note that you may **not** modify the signature of any methods or classes that we
provide to you, but you're free to add helper methods. Also, you should only modify
`table/stats/Histogram.java` and `query/QueryPlan.java` in this part.

