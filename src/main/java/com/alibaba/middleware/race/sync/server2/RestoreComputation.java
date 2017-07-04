package com.alibaba.middleware.race.sync.server2;

import com.alibaba.middleware.race.sync.server2.operations.*;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;

import java.util.ArrayList;
import java.util.concurrent.*;

import static com.alibaba.middleware.race.sync.server2.PipelinedComputation.EVAL_WORKER_NUM;
import static com.alibaba.middleware.race.sync.server2.PipelinedComputation.finalResultMap;

/**
 * Created by yche on 6/18/17.
 */
public class RestoreComputation {
    static int WORKER_NUM = 8;
    public static TLongObjectHashMap<LogOperation>[] recordMapArr = new TLongObjectHashMap[WORKER_NUM];

    static {
        for (int i = 0; i < WORKER_NUM; i++) {
            recordMapArr[i] = new TLongObjectHashMap<>(24 * 1024 * 1024 / WORKER_NUM);
        }
    }

    public static TLongSet inRangeRecordSet = new TLongHashSet(4 * 1024 * 1024);

    static void computeDatabase(final ArrayList<LogOperation> logOperations, int index) {
        for (LogOperation logOperation : logOperations) {
            if (logOperation instanceof UpdateKeyOperation) {
                long pk = ((UpdateKeyOperation) logOperation).changedKey;
                recordMapArr[index].put(pk, new InsertOperation(((UpdateKeyOperation) logOperation).changedKey)); //5
            } else if (logOperation instanceof UpdateOperation) {
                InsertOperation insertOperation = (InsertOperation) recordMapArr[index].get(logOperation.relevantKey); //2
                insertOperation.mergeAnother((UpdateOperation) logOperation); //3
            } else if (logOperation instanceof InsertOperation) {
                recordMapArr[index].put(logOperation.relevantKey, logOperation); //1
            }
        }
    }

    static void compute(final ArrayList<LogOperation> logOperations) {
        for (LogOperation logOperation : logOperations) {
            if (logOperation instanceof UpdateKeyOperation) {
                if (PipelinedComputation.isKeyInRange(logOperation.relevantKey)) {
                    inRangeRecordSet.remove(logOperation.relevantKey);
                }

                if (PipelinedComputation.isKeyInRange(((UpdateKeyOperation) logOperation).changedKey)) {
                    inRangeRecordSet.add(((UpdateKeyOperation) logOperation).changedKey);
                }
            } else if (logOperation instanceof InsertOperation) {
                if (PipelinedComputation.isKeyInRange(logOperation.relevantKey)) {
                    inRangeRecordSet.add(logOperation.relevantKey);
                }
            } else if (logOperation instanceof DeleteOperation) {
                if (PipelinedComputation.isKeyInRange(logOperation.relevantKey)) {
                    inRangeRecordSet.remove(logOperation.relevantKey);
                }
            }
        }
    }

    private static class EvalTask implements Runnable {
        int start;
        int end;
        long[] logOperations;

        EvalTask(int start, int end, long[] logOperations) {
            this.start = start;
            this.end = end;
            this.logOperations = logOperations;
        }

        @Override
        public void run() {
            for (int i = start; i < end; i++) {
                long key = logOperations[i];
                InsertOperation insertOperation = (InsertOperation) recordMapArr[(int) (key % WORKER_NUM)].get(key);
                finalResultMap.put(insertOperation.relevantKey, insertOperation.getOneLineBytesEfficient());
            }
        }
    }

    // used by master thread
    static void parallelEvalAndSend(ExecutorService evalThreadPool) {
        long[] insertOperations = inRangeRecordSet.toArray();
        int avgTask = insertOperations.length / EVAL_WORKER_NUM;
        for (int i = 0; i < insertOperations.length; i += avgTask) {
            evalThreadPool.execute(new EvalTask(i, Math.min(i + avgTask, insertOperations.length), insertOperations));
        }
    }
}
