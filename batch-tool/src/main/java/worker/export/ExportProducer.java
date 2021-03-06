/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package worker.export;

import com.alibaba.druid.util.JdbcUtils;
import com.lmax.disruptor.RingBuffer;
import model.config.QuoteEncloseMode;
import model.db.FieldMetaInfo;
import model.db.TableFieldMetaInfo;
import model.db.TableTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.DataSourceUtil;
import util.FileUtil;
import worker.util.ExportUtil;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static model.config.GlobalVar.EMIT_BATCH_SIZE;

public class ExportProducer extends BaseExportWorker {
    private static final Logger logger = LoggerFactory.getLogger(ExportProducer.class);

    private final RingBuffer<ExportEvent> ringBuffer;

    private final CountDownLatch countDownLatch;
    private final AtomicInteger emittedDataCounter;

    private final boolean collectFragmentEnabled;

    private Queue<ExportEvent> fragmentQueue;
    private String whereCondition;

    private Semaphore permitted;

    public ExportProducer(DataSource druid, TableTopology topology,
                          TableFieldMetaInfo tableFieldMetaInfo,
                          RingBuffer<ExportEvent> ringBuffer,
                          String separator, CountDownLatch countDownLatch,
                          AtomicInteger emittedDataCounter,
                          boolean collectFragmentEnabled,
                          QuoteEncloseMode quoteEncloseMode) {
        super(druid, topology, tableFieldMetaInfo, separator, quoteEncloseMode);
        this.ringBuffer = ringBuffer;
        this.countDownLatch = countDownLatch;
        this.emittedDataCounter = emittedDataCounter;
        this.collectFragmentEnabled = collectFragmentEnabled;
    }

    @Override
    public void run() {
        beforeRun();
        try {
            produceData();
        } catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
        } finally {
            afterRun();
        }
    }

    private void beforeRun() {
        if (permitted != null) {
            permitted.acquireUninterruptibly();
        }
    }

    private void afterRun() {
        if (permitted != null) {
            permitted.release();
        }
        countDownLatch.countDown();
    }

    public void produceData() {
        List<FieldMetaInfo> metaInfoList = tableFieldMetaInfo.getFieldMetaInfoList();
        String sql = ExportUtil.getDirectSql(topology, metaInfoList, whereCondition);

        // ?????????
        int colNum;
        // ?????????????????????
        int bufferedRowNum = 0;
        byte[] value;
        // ??????????????????????????????os???????????????
        ByteArrayOutputStream os = new ByteArrayOutputStream(metaInfoList.size() * 8);
        Connection conn = null;
        Statement stmt = null;
        ResultSet resultSet = null;
        try {
            conn = druid.getConnection();
            stmt = DataSourceUtil.createStreamingStatement(conn);
            logger.info("{} ??????????????????", topology);
            resultSet = stmt.executeQuery(sql);
            colNum = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                for (int i = 1; i < colNum; i++) {
                    value = resultSet.getBytes(i);
                    writeFieldValue(os, value, isStringTypeList.get(i - 1));
                    // ???????????????
                    os.write(separator);
                }
                value = resultSet.getBytes(colNum);
                writeFieldValue(os, value, isStringTypeList.get(colNum - 1));
                // ???????????????
                os.write(FileUtil.SYS_NEW_LINE_BYTE);
                bufferedRowNum++;
                if (bufferedRowNum == EMIT_BATCH_SIZE) {
                    emitData(os.toByteArray());
                    os.reset();
                    bufferedRowNum = 0;
                }
            }
            if (bufferedRowNum != 0) {
                // ?????????????????????
                if (collectFragmentEnabled) {
                    emitRemainData(os.toByteArray());
                } else {
                    emitData(os.toByteArray());
                }
            }
            logger.info("{} ????????????", topology);
        } catch (SQLException | IOException e) {
            e.printStackTrace();
            logger.error(e.getMessage());
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * ????????????????????????
     *
     * @param data ???????????????????????????
     */
    private void emitData(byte[] data) {
        long sequence;
        sequence = ringBuffer.next();
        try {
            // ???Event????????????
            ExportEvent event = ringBuffer.get(sequence);
            event.setData(data);
        } finally {
            emittedDataCounter.getAndIncrement();
            ringBuffer.publish(sequence);
        }
    }

    /**
     * ?????????????????????????????????
     */
    private void emitRemainData(byte[] data) {
        fragmentQueue.add(new ExportEvent(data));
    }

    public String getWhereCondition() {
        return whereCondition;
    }

    public void setWhereCondition(String whereCondition) {
        this.whereCondition = whereCondition;
    }

    public Queue<ExportEvent> getFragmentQueue() {
        return fragmentQueue;
    }

    public void setFragmentQueue(Queue<ExportEvent> fragmentQueue) {
        this.fragmentQueue = fragmentQueue;
    }

    public void setPermitted(Semaphore permitted) {
        this.permitted = permitted;
    }
}
