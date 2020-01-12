package edu.berkeley.cs186.database.query;

import java.util.*;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.table.Record;

class SortMergeOperator extends JoinOperator {
    SortMergeOperator(QueryOperator leftSource,
                      QueryOperator rightSource,
                      String leftColumnName,
                      String rightColumnName,
                      TransactionContext transaction) {
        super(leftSource, rightSource, leftColumnName, rightColumnName, transaction, JoinType.SORTMERGE);

        this.stats = this.estimateStats();
        this.cost = this.estimateIOCost();
    }

    @Override
    public Iterator<Record> iterator() {
        return new SortMergeIterator();
    }

    @Override
    public int estimateIOCost() {
        //does nothing
        return 0;
    }

    /**
     * An implementation of Iterator that provides an iterator interface for this operator.
     *
     * Before proceeding, you should read and understand SNLJOperator.java
     *    You can find it in the same directory as this file.
     *
     * Word of advice: try to decompose the problem into distinguishable sub-problems.
     *    This means you'll probably want to add more methods than those given (Once again,
     *    SNLJOperator.java might be a useful reference).
     *
     */
    private class SortMergeIterator extends JoinIterator {
        /**
        * Some member variables are provided for guidance, but there are many possible solutions.
        * You should implement the solution that's best for you, using any member variables you need.
        * You're free to use these member variables, but you're not obligated to.
        */
        private BacktrackingIterator<Record> leftIterator;
        private BacktrackingIterator<Record> rightIterator;
        private Record leftRecord;
        private Record nextRecord;
        private Record rightRecord;
        private boolean marked;

        private SortMergeIterator() {
            super();            
            
            this.rightIterator = SortMergeOperator.this.getRecordIterator(getRightSortName());
            this.leftIterator = SortMergeOperator.this.getRecordIterator(getLeftSortName());
            
            this.leftRecord = leftIterator.hasNext() ? leftIterator.next() : null;
            this.rightRecord = rightIterator.hasNext() ? rightIterator.next() : null;

//            if (rightRecord != null) {
//                rightIterator.markPrev();
//            } else { return; }
            
            marked = false;
            
            try {
                fetchNextRecord();
            } catch (NoSuchElementException e) {
                this.nextRecord = null;
            }
        }
        
        
        private String getRightSortName() {
        	SortOperator sor = new SortOperator(SortMergeOperator.this.getTransaction(), this.getRightTableName(), new RightRecordComparator());
        	return(sor.sort());        	
        }
        
        private String getLeftSortName() {
        	SortOperator sol = new SortOperator(SortMergeOperator.this.getTransaction(), this.getLeftTableName(), new LeftRecordComparator());
        	return(sol.sort());        	
        }
        
        private void nextLeftRecord() {
            this.leftRecord = leftIterator.hasNext() ? leftIterator.next() : null;
        }
        
        private void nextRightRecord() {
            this.rightRecord = rightIterator.hasNext() ? rightIterator.next() : null;
        }
        
        private void resetRightRecord() {
            this.rightIterator.reset();
            assert(rightIterator.hasNext());
            rightRecord = rightIterator.next();
        }
        
        private boolean checkNotNull() {
        	return (leftRecord != null) && (rightRecord != null);
        }
        
        private Record joinRecords(Record leftRecord, Record rightRecord) {
            List<DataBox> leftValues = new ArrayList<>(leftRecord.getValues());
            List<DataBox> rightValues = new ArrayList<>(rightRecord.getValues());
            leftValues.addAll(rightValues);
            return new Record(leftValues);
        }
        
        
        private void fetchNextRecord() {
//            if (this.leftRecord == null) { throw new NoSuchElementException("No new record to fetch"); }
            this.nextRecord = null;
            LeftRecordComparator lcom = new LeftRecordComparator();
            do {
            	if (!marked) {
            		if (!checkNotNull()) {
            			break;
            		}
            		while (checkNotNull() && lcom.compare(leftRecord, rightRecord) < 0) {
            			nextLeftRecord();
            		}
            		while (checkNotNull() && lcom.compare(leftRecord, rightRecord) > 0) {
            			nextRightRecord();
            		}
            		marked = true;
//            		if ((leftRecord == null) || (rightRecord == null)) {
//            			break;
//            		}
            		rightIterator.markPrev();
            	}
            	
            	if (checkNotNull() && lcom.compare(leftRecord, rightRecord) == 0) {
                    this.nextRecord = joinRecords(leftRecord, rightRecord);
            		nextRightRecord();
            	} else{
            		resetRightRecord();
            		nextLeftRecord();
            		marked = false;
            	}
            } while (!hasNext());
        }

        /**
         * Checks if there are more record(s) to yield
         *
         * @return true if this iterator has another record to yield, otherwise false
         */
        @Override
        public boolean hasNext() {
            return this.nextRecord != null;
//        	if (nextRecord != null) {
//        		return true;
//        	} else if (leftRecord != null) {
//        		return true;
//        	} else {
//        		return false;
//        	}
        }

        /**
         * Yields the next record of this iterator.
         *
         * @return the next Record
         * @throws NoSuchElementException if there are no more Records to yield
         */
        @Override
        public Record next() {
        	if (!this.hasNext()) {
                throw new NoSuchElementException();
            }

            Record nextRecord = this.nextRecord;
            
//            marked = false;
            try {
                this.fetchNextRecord();
            } catch (NoSuchElementException e) {
                this.nextRecord = null;
            }
            return nextRecord;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private class LeftRecordComparator implements Comparator<Record> {
            @Override
            public int compare(Record o1, Record o2) {
                return o1.getValues().get(SortMergeOperator.this.getLeftColumnIndex()).compareTo(
                           o2.getValues().get(SortMergeOperator.this.getLeftColumnIndex()));
            }
        }

        private class RightRecordComparator implements Comparator<Record> {
            @Override
            public int compare(Record o1, Record o2) {
                return o1.getValues().get(SortMergeOperator.this.getRightColumnIndex()).compareTo(
                           o2.getValues().get(SortMergeOperator.this.getRightColumnIndex()));
            }
        }
    }
}
