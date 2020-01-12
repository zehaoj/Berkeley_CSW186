package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.TimeoutScaling;
import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.Transaction.Status;
import edu.berkeley.cs186.database.categories.HW5Tests;
import edu.berkeley.cs186.database.categories.StudentTests;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.io.DiskSpaceManagerImpl;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.BufferManagerImpl;
import edu.berkeley.cs186.database.memory.LRUEvictionPolicy;
import org.junit.*;
import org.junit.experimental.categories.Category;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import java.util.*;
import java.util.function.Consumer;

import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.*;

/**
 * File for student tests for HW5 (Recovery). Tests are run through
 * TestARIESStudentRunner for grading purposes.
 */
@Category({HW5Tests.class, StudentTests.class})
public class TestARIESStudent {
    private String testDir;
    private RecoveryManager recoveryManager;
    private final Queue<Consumer<LogRecord>> redoMethods = new ArrayDeque<>();

    // 1 second per test
    @Rule
    public TestRule globalTimeout = new DisableOnDebug(Timeout.millis((long) (
                1000 * TimeoutScaling.factor)));

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void setup() throws Exception {
        testDir = tempFolder.newFolder("test-dir").getAbsolutePath();
        recoveryManager = loadRecoveryManager(testDir);
        DummyTransaction.cleanupTransactions();
        LogRecord.onRedoHandler(t -> {});
    }

    @After
    public void cleanup() throws Exception {}

    @Test
    public void testStudentAnalysis() throws Exception {
        // TODO(hw5): write your own test on restartAnalysis only
        // You should use loadRecoveryManager instead of new ARIESRecoveryManager(..) to
        // create the recovery manager, and use runAnalysis(inner) instead of
        // inner.restartAnalysis() to call the analysis routine.
    	byte[] before = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
        byte[] after = new byte[] { (byte) 0xBA, (byte) 0xAD, (byte) 0xF0, (byte) 0x0D };

        LogManager logManager = getLogManager(recoveryManager);

        DummyTransaction transaction1 = DummyTransaction.create(1L);
        DummyTransaction transaction2 = DummyTransaction.create(2L);
        DummyTransaction transaction3 = DummyTransaction.create(3L);

        List<Long> LSNs = new ArrayList<>();
        
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 100003L, 0L, (short) 0, before, after))); // 0
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 100001L, LSNs.get(0), (short) 0, before, after))); // 1
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(2L, 100002L, 0L, (short) 0, before, after))); // 2
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(3L, 100001L, 0L, (short) 0, before, after))); // 3
        LSNs.add(logManager.appendToLog(new BeginCheckpointLogRecord(9876543210L))); // 4
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(3L, 100003L, LSNs.get(3), (short) 0, before, after))); // 5
        LSNs.add(logManager.appendToLog(new AbortTransactionLogRecord(3L, LSNs.get(5)))); // 6
        Map<Long, Long> curDirtyPageTable = new HashMap<>();
        curDirtyPageTable.put(100001L, LSNs.get(3));
        curDirtyPageTable.put(100003L, LSNs.get(0));
        Map<Long, Pair<Status, Long>> curTransactionTable = new HashMap<>();
        curTransactionTable.put(1L, new Pair<>(Status.RUNNING, LSNs.get(0)));
        curTransactionTable.put(2L, new Pair<>(Status.RUNNING, LSNs.get(2)));
        curTransactionTable.put(3L, new Pair<>(Status.RUNNING, LSNs.get(3)));
        Map<Long, List<Long>> curTouchedTable = new HashMap<>();
        LSNs.add(logManager.appendToLog(new EndCheckpointLogRecord(curDirtyPageTable, curTransactionTable, curTouchedTable))); // 7
        LSNs.add(logManager.appendToLog(new UndoUpdatePageLogRecord(3L, 100003L, LSNs.get(6), 
        		LSNs.get(5), (short) 0, after))); // 8
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 100004L, 
        		LSNs.get(1), (short) 0, before, after))); // 9
        LSNs.add(logManager.appendToLog(new CommitTransactionLogRecord(1L, LSNs.get(9)))); // 10
        LSNs.add(logManager.appendToLog(new EndTransactionLogRecord(1L, LSNs.get(10)))); // 11

        
        shutdownRecoveryManager(recoveryManager);

        recoveryManager = loadRecoveryManager(testDir);

        logManager = getLogManager(recoveryManager);
        Map<Long, Long> dirtyPageTable = getDirtyPageTable(recoveryManager);
        Map<Long, TransactionTableEntry> transactionTable = getTransactionTable(recoveryManager);

        runAnalysis(recoveryManager);

        // Xact table
        assertFalse(transactionTable.containsKey(1L));
        assertTrue(transactionTable.containsKey(2L));
        assertTrue(transactionTable.containsKey(3L));

        // DPT
        assertTrue(dirtyPageTable.containsKey(100001L));
        assertEquals((long) LSNs.get(3), (long) dirtyPageTable.get(100001L));
        assertTrue(dirtyPageTable.containsKey(100003L));
        assertTrue(dirtyPageTable.containsKey(100004L));
        assertEquals((long) LSNs.get(9), (long) dirtyPageTable.get(100004L));

        // status/cleanup
        assertEquals(Transaction.Status.RECOVERY_ABORTING, transaction2.getStatus());
        assertTrue(transaction1.cleanedUp);
        assertEquals(Transaction.Status.RECOVERY_ABORTING, transaction3.getStatus());
        
  
    }

    @Test
    public void testStudentRedo() throws Exception {
        // TODO(hw5): write your own test on restartRedo only
        // You should use loadRecoveryManager instead of new ARIESRecoveryManager(..) to
        // create the recovery manager, and use runRedo(inner) instead of
        // inner.restartRedo() to call the analysis routine.
    	byte[] before = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
        byte[] after = new byte[] { (byte) 0xBA, (byte) 0xAD, (byte) 0xF0, (byte) 0x0D };

        LogManager logManager = getLogManager(recoveryManager);
        DiskSpaceManager dsm = getDiskSpaceManager(recoveryManager);
        BufferManager bm = getBufferManager(recoveryManager);

        DummyTransaction transaction1 = DummyTransaction.create(1L);

        List<Long> LSNs = new ArrayList<>();
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000003L, 0L, (short) 0, before, after))); // 0
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000001L, LSNs.get(0), (short) 0, before, after))); // 1
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(2L, 10000000002L, 0L, (short) 0, before, after))); // 2
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(3L, 10000000001L, 0L, (short) 0, before, after))); // 3
        LSNs.add(logManager.appendToLog(new BeginCheckpointLogRecord(9876543210L))); // 4
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(3L, 10000000003L, LSNs.get(3), (short) 0, before, after))); // 5
        LSNs.add(logManager.appendToLog(new AbortTransactionLogRecord(3L, LSNs.get(5)))); // 6
        Map<Long, Long> curDirtyPageTable = new HashMap<>();
        curDirtyPageTable.put(100001L, LSNs.get(3));
        curDirtyPageTable.put(100003L, LSNs.get(0));
        Map<Long, Pair<Status, Long>> curTransactionTable = new HashMap<>();
        curTransactionTable.put(1L, new Pair<>(Status.RUNNING, LSNs.get(0)));
        curTransactionTable.put(2L, new Pair<>(Status.RUNNING, LSNs.get(2)));
        curTransactionTable.put(3L, new Pair<>(Status.RUNNING, LSNs.get(3)));
        Map<Long, List<Long>> curTouchedTable = new HashMap<>();
        LSNs.add(logManager.appendToLog(new EndCheckpointLogRecord(curDirtyPageTable, curTransactionTable, curTouchedTable))); // 7
        LSNs.add(logManager.appendToLog(new UndoUpdatePageLogRecord(3L, 10000000003L, LSNs.get(6), 
        		LSNs.get(5), (short) 0, after))); // 8
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000004L, 
        		LSNs.get(1), (short) 0, before, after))); // 9
        LSNs.add(logManager.appendToLog(new CommitTransactionLogRecord(1L, LSNs.get(9)))); // 10
        LSNs.add(logManager.appendToLog(new EndTransactionLogRecord(1L, LSNs.get(10)))); // 11

        shutdownRecoveryManager(recoveryManager);

        // load from disk again
        recoveryManager = loadRecoveryManager(testDir);

        // set up dirty page table - xact table is empty (transaction ended)
        Map<Long, Long> dirtyPageTable = getDirtyPageTable(recoveryManager);
        dirtyPageTable.put(10000000001L, LSNs.get(3));
        dirtyPageTable.put(10000000003L, LSNs.get(0));
        dirtyPageTable.put(10000000004L, LSNs.get(9));
        Map<Long, Pair<Status, Long>> transactionTable = new HashMap<>();
        transactionTable.put(2L, new Pair<>(Status.RUNNING, LSNs.get(2)));
        transactionTable.put(3L, new Pair<>(Status.ABORTING, LSNs.get(8)));

        // set up checks for redo - these get called in sequence with each LogRecord#redo call
        setupRedoChecks(Arrays.asList(
                            (LogRecord record) -> assertEquals((long) LSNs.get(0), (long) record.LSN),
                            (LogRecord record) -> assertEquals((long) LSNs.get(3), (long) record.LSN),
                            (LogRecord record) -> assertEquals((long) LSNs.get(5), (long) record.LSN),
                            (LogRecord record) -> assertEquals((long) LSNs.get(8), (long) record.LSN),
                            (LogRecord record) -> assertEquals((long) LSNs.get(9), (long) record.LSN)
        		));

        runRedo(recoveryManager);

        finishRedoChecks();
    }

    @Test
    public void testStudentUndo() throws Exception {
        // TODO(hw5): write your own test on restartUndo only
        // You should use loadRecoveryManager instead of new ARIESRecoveryManager(..) to
        // create the recovery manager, and use runUndo(inner) instead of
        // inner.restartUndo() to call the analysis routine.
    	byte[] before = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
        byte[] after = new byte[] { (byte) 0xBA, (byte) 0xAD, (byte) 0xF0, (byte) 0x0D };

        LogManager logManager = getLogManager(recoveryManager);
        DiskSpaceManager dsm = getDiskSpaceManager(recoveryManager);
        BufferManager bm = getBufferManager(recoveryManager);

        DummyTransaction transaction1 = DummyTransaction.create(1L);

        List<Long> LSNs = new ArrayList<>();
        LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000001L, 0L, (short) 0, before,
                after))); // 0
		LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000002L, LSNs.get(0), (short) 1,
		                before, after))); // 1
		LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000003L, LSNs.get(1), (short) 2,
		                before, after))); // 2
		LSNs.add(logManager.appendToLog(new UpdatePageLogRecord(1L, 10000000004L, LSNs.get(2), (short) 3,
		                before, after))); // 3
		LSNs.add(logManager.appendToLog(new UndoUpdatePageLogRecord(1L, 10000000004L, LSNs.get(3), LSNs.get(2),
				(short) 0, after)));
		LSNs.add(logManager.appendToLog(new AbortTransactionLogRecord(1L, LSNs.get(3)))); // 4

		for (int i = 0; i < 4; ++i) {
            logManager.fetchLogRecord(LSNs.get(i)).redo(dsm, bm);
        }

        // flush everything - recovery tests should always start
        // with a clean load from disk, and here we want everything sent to disk first.
        // Note: this does not call RecoveryManager#close - it only closes the
        // buffer manager and disk space manager.
        shutdownRecoveryManager(recoveryManager);

        // load from disk again
        recoveryManager = loadRecoveryManager(testDir);

        // set up xact table - leaving DPT empty
        Map<Long, TransactionTableEntry> transactionTable = getTransactionTable(recoveryManager);
        TransactionTableEntry entry1 = new TransactionTableEntry(transaction1);
        entry1.lastLSN = LSNs.get(4);
        entry1.touchedPages = new HashSet<>(Arrays.asList(10000000001L, 10000000002L, 10000000003L,
                                            10000000004L));
        entry1.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
        transactionTable.put(1L, entry1);

        

        // set up checks for undo - these get called in sequence with each LogRecord#redo call
        // (which should be called on CLRs)
        setupRedoChecks(Arrays.asList(
                (LogRecord record) -> {
                    assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
                    assertNotNull("log record not appended to log yet", record.LSN);
                    assertEquals((long) record.LSN, transactionTable.get(1L).lastLSN);
                    assertEquals(Optional.of(10000000003L), record.getPageNum());
                },
                (LogRecord record) -> {
                    assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
                    assertNotNull("log record not appended to log yet", record.LSN);
                    assertEquals((long) record.LSN, transactionTable.get(1L).lastLSN);
                    assertEquals(Optional.of(10000000002L), record.getPageNum());
                },
                (LogRecord record) -> {
                    assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
                    assertNotNull("log record not appended to log yet", record.LSN);
                    assertEquals((long) record.LSN, transactionTable.get(1L).lastLSN);
                    assertEquals(Optional.of(10000000001L), record.getPageNum());
                }
                                ));

        runUndo(recoveryManager);

//        finishRedoChecks();

        assertEquals(Transaction.Status.COMPLETE, transaction1.getStatus());
    	
    }

    @Test
    public void testStudentIntegration() throws Exception {
        // TODO(hw5): write your own test on all of RecoveryManager
        // You should use loadRecoveryManager instead of new ARIESRecoveryManager(..) to
        // create the recovery manager.
    	byte[] before = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
        byte[] after = new byte[] { (byte) 0xBA, (byte) 0xAD, (byte) 0xF0, (byte) 0x0D };

        Transaction transaction1 = DummyTransaction.create(1L);
        recoveryManager.startTransaction(transaction1);
        Transaction transaction2 = DummyTransaction.create(2L);
        recoveryManager.startTransaction(transaction2);
        Transaction transaction3 = DummyTransaction.create(3L);
        recoveryManager.startTransaction(transaction3);

        long[] LSNs = new long[] {
            recoveryManager.logPageWrite(1L, 10000000001L, (short) 0, before, after), // 0
            recoveryManager.logPageWrite(2L, 10000000003L, (short) 0, before, after), // 1
            recoveryManager.commit(1L), // 2
            recoveryManager.logPageWrite(3L, 10000000004L, (short) 0, before, after), // 3
            recoveryManager.logPageWrite(2L, 10000000001L, (short) 0, after, before), // 4
            recoveryManager.end(1L), // 5
            recoveryManager.logPageWrite(3L, 10000000002L, (short) 0, before, after), // 6
            recoveryManager.abort(2), // 7
        };

        // flush everything - recovery tests should always start
        // with a clean load from disk, and here we want everything sent to disk first.
        // Note: this does not call RecoveryManager#close - it only closes the
        // buffer manager and disk space manager.
        shutdownRecoveryManager(recoveryManager);

        // load from disk again
        recoveryManager = loadRecoveryManager(testDir);

        LogManager logManager = getLogManager(recoveryManager);

        recoveryManager.restart().run(); // run everything in restart recovery

        Iterator<LogRecord> iter = logManager.iterator();
        assertEquals(LogType.MASTER, iter.next().getType());
        assertEquals(LogType.BEGIN_CHECKPOINT, iter.next().getType());
        assertEquals(LogType.END_CHECKPOINT, iter.next().getType());
        assertEquals(LogType.UPDATE_PAGE, iter.next().getType());
        assertEquals(LogType.UPDATE_PAGE, iter.next().getType());
        assertEquals(LogType.COMMIT_TRANSACTION, iter.next().getType());
        assertEquals(LogType.UPDATE_PAGE, iter.next().getType());
        assertEquals(LogType.UPDATE_PAGE, iter.next().getType());
        assertEquals(LogType.END_TRANSACTION, iter.next().getType());
        assertEquals(LogType.UPDATE_PAGE, iter.next().getType());
        assertEquals(LogType.ABORT_TRANSACTION, iter.next().getType());

        LogRecord record = iter.next();
        assertEquals(LogType.ABORT_TRANSACTION, record.getType());
        long LSN8 = record.LSN;

        record = iter.next();
        assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
        assertEquals(LSN8, (long) record.getPrevLSN().orElseThrow(NoSuchElementException::new));
        assertEquals(LSNs[3], (long) record.getUndoNextLSN().orElseThrow(NoSuchElementException::new));
        long LSN9 = record.LSN;

        record = iter.next();
        assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
        assertEquals(LSNs[7], (long) record.getPrevLSN().orElseThrow(NoSuchElementException::new));
        assertEquals(LSNs[1], (long) record.getUndoNextLSN().orElseThrow(NoSuchElementException::new));
        long LSN10 = record.LSN;

        record = iter.next();
        assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
        assertEquals(LSN9, (long) record.getPrevLSN().orElseThrow(NoSuchElementException::new));
        assertEquals(0L, (long) record.getUndoNextLSN().orElseThrow(NoSuchElementException::new));
        assertEquals(LogType.END_TRANSACTION, iter.next().getType());

        record = iter.next();
        assertEquals(LogType.UNDO_UPDATE_PAGE, record.getType());
        assertEquals(LSN10, (long) record.getPrevLSN().orElseThrow(NoSuchElementException::new));
        assertEquals(0L, (long) record.getUndoNextLSN().orElseThrow(NoSuchElementException::new));

        assertEquals(LogType.END_TRANSACTION, iter.next().getType());
        assertEquals(LogType.BEGIN_CHECKPOINT, iter.next().getType());
        assertEquals(LogType.END_CHECKPOINT, iter.next().getType());
        assertFalse(iter.hasNext());
    }

    // TODO(hw5): add as many (ungraded) tests as you want for testing!

    @Test
    public void testCase() throws Exception {
        // TODO(hw5): write your own test! (ungraded)
    }

    @Test
    public void anotherTestCase() throws Exception {
        // TODO(hw5): write your own test!!! (ungraded)
    }

    @Test
    public void yetAnotherTestCase() throws Exception {
        // TODO(hw5): write your own test!!!!! (ungraded)
    }

    /*************************************************************************
     * Helpers for writing tests.                                            *
     * Do not change the signature of any of the following methods.          *
     *************************************************************************/

    /**
     * Helper to set up checks for redo. The first call to LogRecord.redo will
     * call the first method in METHODS, the second call to the second method in METHODS,
     * and so on. Call this method before the redo pass, and call finishRedoChecks
     * after the redo pass.
     */
    private void setupRedoChecks(Collection<Consumer<LogRecord>> methods) {
        for (final Consumer<LogRecord> method : methods) {
            redoMethods.add(record -> {
                method.accept(record);
                LogRecord.onRedoHandler(redoMethods.poll());
            });
        }
        redoMethods.add(record -> {
            fail("LogRecord#redo() called too many times");
        });
        LogRecord.onRedoHandler(redoMethods.poll());
    }

    /**
     * Helper to finish checks for redo. Call this after the redo pass (or undo pass)-
     * if not enough redo calls were performed, an error is thrown.
     *
     * If setupRedoChecks is used for the redo pass, and this method is not called before
     * the undo pass, and the undo pass calls undo at least once, an error may be incorrectly thrown.
     */
    private void finishRedoChecks() {
        assertTrue("LogRecord#redo() not called enough times", redoMethods.isEmpty());
        LogRecord.onRedoHandler(record -> {});
    }

    /**
     * Loads the recovery manager from disk.
     * @param dir testDir
     * @return recovery manager, loaded from disk
     */
    protected RecoveryManager loadRecoveryManager(String dir) throws Exception {
        RecoveryManager recoveryManager = new ARIESRecoveryManagerNoLocking(
            new DummyLockContext(new Pair<>("database", 0L)),
            DummyTransaction::create
        );
        DiskSpaceManager diskSpaceManager = new DiskSpaceManagerImpl(dir, recoveryManager);
        BufferManager bufferManager = new BufferManagerImpl(diskSpaceManager, recoveryManager, 32,
                new LRUEvictionPolicy());
        boolean isLoaded = true;
        try {
            diskSpaceManager.allocPart(0);
            diskSpaceManager.allocPart(1);
            for (int i = 0; i < 10; ++i) {
                diskSpaceManager.allocPage(DiskSpaceManager.getVirtualPageNum(1, i));
            }
            isLoaded = false;
        } catch (IllegalStateException e) {
            // already loaded
        }
        recoveryManager.setManagers(diskSpaceManager, bufferManager);
        if (!isLoaded) {
            recoveryManager.initialize();
        }
        return recoveryManager;
    }

    /**
     * Flushes everything to disk, but does not call RecoveryManager#shutdown. Similar
     * to pulling the plug on the database at a time when no changes are in memory. You
     * can simulate a shutdown where certain changes _are_ in memory, by simply never
     * applying them (i.e. write a log record, but do not make the changes on the
     * buffer manager/disk space manager).
     */
    protected void shutdownRecoveryManager(RecoveryManager recoveryManager) throws Exception {
        ARIESRecoveryManager arm = (ARIESRecoveryManager) recoveryManager;
        arm.logManager.close();
        arm.bufferManager.evictAll();
        arm.bufferManager.close();
        arm.diskSpaceManager.close();
        DummyTransaction.cleanupTransactions();
    }

    protected BufferManager getBufferManager(RecoveryManager recoveryManager) throws Exception {
        return ((ARIESRecoveryManager) recoveryManager).bufferManager;
    }

    protected DiskSpaceManager getDiskSpaceManager(RecoveryManager recoveryManager) throws Exception {
        return ((ARIESRecoveryManager) recoveryManager).diskSpaceManager;
    }

    protected LogManager getLogManager(RecoveryManager recoveryManager) throws Exception {
        return ((ARIESRecoveryManager) recoveryManager).logManager;
    }

    protected List<String> getLockRequests(RecoveryManager recoveryManager) throws Exception {
        return ((ARIESRecoveryManager) recoveryManager).lockRequests;
    }

    protected long getTransactionCounter(RecoveryManager recoveryManager) throws Exception {
        return ((ARIESRecoveryManagerNoLocking) recoveryManager).transactionCounter;
    }

    protected Map<Long, Long> getDirtyPageTable(RecoveryManager recoveryManager) throws Exception {
        return ((ARIESRecoveryManager) recoveryManager).dirtyPageTable;
    }

    protected Map<Long, TransactionTableEntry> getTransactionTable(RecoveryManager recoveryManager)
    throws Exception {
        return ((ARIESRecoveryManager) recoveryManager).transactionTable;
    }

    protected void runAnalysis(RecoveryManager recoveryManager) throws Exception {
        ((ARIESRecoveryManager) recoveryManager).restartAnalysis();
    }

    protected void runRedo(RecoveryManager recoveryManager) throws Exception {
        ((ARIESRecoveryManager) recoveryManager).restartRedo();
    }

    protected void runUndo(RecoveryManager recoveryManager) throws Exception {
        ((ARIESRecoveryManager) recoveryManager).restartUndo();
    }
}
