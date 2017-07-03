package com.alibaba.middleware.race.sync.server2;

import com.alibaba.middleware.race.sync.server2.operations.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.alibaba.middleware.race.sync.Constants.*;
import static com.alibaba.middleware.race.sync.server2.RecordField.fieldSkipLen;

/**
 * Created by yche on 6/18/17.
 * used for scan the byte arr of record string lines
 */
public class RecordScanner {
    // input
    private ByteBuffer mappedByteBuffer;
    private int endIndex;   // exclusive

    // intermediate states
    private final ByteBuffer tmpBuffer = ByteBuffer.allocate(8);
    private int nextIndex; // start from startIndex

    private final ArrayList<LogOperation> localOperations = new ArrayList<>(1024 * 16);
    private Future<?> prevFuture;
    private int primaryKeyDigitNum = 0;

    public RecordScanner() {
    }

    public void reuse(ByteBuffer mappedByteBuffer, int startIndex, int endIndex, Future<?> prevFuture) {
        this.mappedByteBuffer = mappedByteBuffer.asReadOnlyBuffer(); // get a view, with local position, limit
        this.nextIndex = startIndex;
        this.endIndex = endIndex;
        this.prevFuture = prevFuture;
    }

    public void reuse(ByteBuffer mappedByteBuffer, int startIndex, int endIndex) {
        this.mappedByteBuffer = mappedByteBuffer.asReadOnlyBuffer();
        this.nextIndex = startIndex;
        this.endIndex = endIndex;
    }

    private void skipField(int index) {
        switch (index) {
            case 0:
                nextIndex += 4;
                break;
            case 1:
                nextIndex += 4;
                if (mappedByteBuffer.get(nextIndex) != FILED_SPLITTER)
                    nextIndex += 3;
                break;
            case 2:
                nextIndex += 4;
                break;
            default:
                nextIndex += 3;
                while (mappedByteBuffer.get(nextIndex) != FILED_SPLITTER) {
                    nextIndex++;
                }
        }
    }

    private void skipHeader() {
        nextIndex += 20;
        while ((mappedByteBuffer.get(nextIndex)) != FILED_SPLITTER) {
            nextIndex++;
        }
        nextIndex += 34;
    }

    private void skipKey() {
        nextIndex += RecordField.KEY_LEN + 3;
    }

    private void skipNull() {
        nextIndex += 5;
    }

    private void skipFieldForInsert(int index) {
        nextIndex += fieldSkipLen[index];
    }

    private void getNextBytesIntoTmp() {
        nextIndex++;

        tmpBuffer.clear();
        byte myByte;
        while ((myByte = mappedByteBuffer.get(nextIndex)) != FILED_SPLITTER) {
            tmpBuffer.put(myByte);
            nextIndex++;
        }
        tmpBuffer.flip();
    }

    private long getNextLong() {
        nextIndex++;

        byte tmpByte;
        long result = 0L;
        while ((tmpByte = mappedByteBuffer.get(nextIndex)) != FILED_SPLITTER) {
            nextIndex++;
            result = (10 * result) + (tmpByte - '0');
        }
        return result;
    }

    private long getNextLongForUpdate() {
        primaryKeyDigitNum = 0;
        nextIndex++;

        byte tmpByte;
        long result = 0L;
        while ((tmpByte = mappedByteBuffer.get(nextIndex)) != FILED_SPLITTER) {
            nextIndex++;
            primaryKeyDigitNum++;
            result = (10 * result) + (tmpByte - '0');
        }
        return result;
    }

    private int skipFieldName() {
        // stop at '|'
        if (mappedByteBuffer.get(nextIndex + 1) == 'f') {
            nextIndex += 15;
            return 0;
        } else if (mappedByteBuffer.get(nextIndex + 1) == 'l') {
            nextIndex += 14;
            return 1;
        } else {
            if (mappedByteBuffer.get(nextIndex + 2) == 'e') {
                nextIndex += 8;
                return 2;
            } else if (mappedByteBuffer.get(nextIndex + 6) == ':') {
                nextIndex += 10;
                return 3;
            } else {
                nextIndex += 11;
                return 4;
            }
        }
    }

    private LogOperation scanOneRecord() {
        // 1st: skip: mysql, ts, schema, table
        skipHeader();

        // 2nd: parse KeyOperation
        byte operation = mappedByteBuffer.get(nextIndex + 1);
        LogOperation logOperation;
        // skip one splitter and operation byte
        skipKey();

        if (operation == U_OPERATION) {
            // update
            long prevKey = getNextLongForUpdate();

            if (nextIndex + primaryKeyDigitNum + 2 < mappedByteBuffer.limit() &&
                    mappedByteBuffer.get(nextIndex + primaryKeyDigitNum + 1) == '|' &&
                    (mappedByteBuffer.get(nextIndex + primaryKeyDigitNum + 2) == 's' ||
                            mappedByteBuffer.get(nextIndex + primaryKeyDigitNum + 2) == 'f' ||
                            mappedByteBuffer.get(nextIndex + primaryKeyDigitNum + 2) == 'l')) {
                nextIndex += primaryKeyDigitNum + 1;
                logOperation = new UpdateOperation(prevKey);
                int localIndex = skipFieldName();
                skipField(localIndex);
                getNextBytesIntoTmp();
                ((UpdateOperation) logOperation).addData(localIndex, tmpBuffer);
            } else {
                long curKey = getNextLong();
                logOperation = new UpdateKeyOperation(prevKey, curKey);
            }
        } else if (operation == I_OPERATION) {
            // insert: pre(null) -> cur
            skipNull();
            logOperation = new InsertOperation(getNextLong());

            int localIndex = 0;
            while (mappedByteBuffer.get(nextIndex + 1) != LINE_SPLITTER) {
                skipFieldForInsert(localIndex);
                skipNull();
                getNextBytesIntoTmp();
                ((InsertOperation) logOperation).addData(localIndex, tmpBuffer);
                localIndex++;
            }
        } else {
            // delete: pre -> cur(null)
            logOperation = new DeleteOperation(getNextLong());
            skipNull();
            while (mappedByteBuffer.get(nextIndex + 1) != LINE_SPLITTER) {
                int localIndex = skipFieldName();
                skipField(localIndex);
                skipNull();
            }
        }

        // skip '|' and `\n`
        nextIndex += 2;
        return logOperation;
    }

    void compute() {
        while (nextIndex < endIndex) {
            localOperations.add(scanOneRecord());
        }
    }

    void waitForSend() throws InterruptedException, ExecutionException {
        // wait for producing tasks
        LogOperation[] logOperations = localOperations.toArray(new LogOperation[0]);
        localOperations.clear();
        prevFuture.get();
        PipelinedComputation.blockingQueue.put(logOperations);
    }
}
