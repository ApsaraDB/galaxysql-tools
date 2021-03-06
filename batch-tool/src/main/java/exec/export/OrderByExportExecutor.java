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

package exec.export;

import cmd.BaseOperateCommand;
import cmd.ExportCommand;
import com.alibaba.druid.pool.DruidDataSource;
import datasource.DataSourceConfig;
import exception.DatabaseException;
import model.config.ExportConfig;
import model.config.GlobalVar;
import model.db.FieldMetaInfo;
import model.db.TableFieldMetaInfo;
import model.db.TableTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.DbUtil;
import util.FileUtil;
import worker.MyThreadPool;
import worker.export.DirectExportWorker;
import worker.export.order.DirectOrderExportWorker;
import worker.export.order.LocalOrderByExportProducer;
import worker.export.order.OrderByExportEvent;
import worker.export.order.OrderByExportProducer;
import worker.export.order.OrderByMergeExportConsumer;
import worker.export.order.ParallelMergeExportConsumer;
import worker.export.order.ParallelOrderByExportEvent;
import worker.factory.ExportWorkerFactory;

import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static model.config.ConfigConstant.APP_NAME;

public class OrderByExportExecutor extends BaseExportExecutor {
    private static final Logger logger = LoggerFactory.getLogger(OrderByExportExecutor.class);

    private ExportCommand command;
    private ExportConfig config;

    public OrderByExportExecutor(DataSourceConfig dataSourceConfig,
                                 DruidDataSource druid,
                                 BaseOperateCommand baseCommand) {
        super(dataSourceConfig, druid, baseCommand);
    }

    @Override
    protected void setCommand(BaseOperateCommand baseCommand) {
        this.command = (ExportCommand) baseCommand;
        this.config = command.getExportConfig();
    }

    @Override
    void exportData() {
        handleExportOrderBy();
    }

    /**
     * ???????????????????????????
     * ?????????????????????????????????
     */
    private void handleExportOrderBy() {
        if (!config.isLocalMerge()) {
            handleExportWithOrderByFromDb();
            return;
        }

        // ?????????????????????????????????
        if (config.isParallelMerge()) {
            handleExportWithOrderByParallelMerge();
        } else {
            doExportWithOrderByLocal();
        }
    }

    /**
     * ??????order by?????????????????????
     * ???????????????merge
     */
    private void doExportWithOrderByLocal() {
        List<TableTopology> topologyList;
        ExportConfig config = command.getExportConfig();
        List<FieldMetaInfo> orderByColumnInfoList;
        for (String tableName : command.getTableNames()) {
            String filePathPrefix = FileUtil.getFilePathPrefix(config.getPath(),
                config.getFilenamePrefix(), tableName);
            try {
                topologyList = DbUtil.getTopology(dataSource.getConnection(), tableName);
                TableFieldMetaInfo tableFieldMetaInfo = DbUtil.getTableFieldMetaInfo(dataSource.getConnection(),
                    getSchemaName(), tableName);
                orderByColumnInfoList = DbUtil.getFieldMetaInfoListByColNames(dataSource.getConnection(), getSchemaName(),
                    tableName, config.getOrderByColumnNameList());
                // ?????????
                final int shardSize = topologyList.size();
                ExecutorService executor = MyThreadPool.createExecutorWithEnsure(APP_NAME, shardSize);
                OrderByExportProducer orderByExportProducer;
                LinkedBlockingQueue[] orderedQueues = new LinkedBlockingQueue[shardSize];
                AtomicBoolean[] finishedList = new AtomicBoolean[shardSize];
                for (int i = 0; i < shardSize; i++) {
                    orderedQueues[i] = new LinkedBlockingQueue<OrderByExportEvent>(GlobalVar.DEFAULT_RING_BUFFER_SIZE);
                    finishedList[i] = new AtomicBoolean(false);
                    orderByExportProducer = new OrderByExportProducer(dataSource, topologyList.get(i),
                        tableFieldMetaInfo, orderedQueues[i], i, config.getOrderByColumnNameList(),
                        finishedList[i]);
                    executor.submit(orderByExportProducer);
                }
                OrderByMergeExportConsumer consumer;
                switch (config.getExportWay()) {
                case MAX_LINE_NUM_IN_SINGLE_FILE:
                    consumer = new OrderByMergeExportConsumer(filePathPrefix,
                        config.getSeparator(), orderByColumnInfoList, orderedQueues, finishedList, config.getLimitNum());
                    break;
                case FIXED_FILE_NUM:
                    // ???????????????????????? ????????????????????????
                    double totalRowCount = DbUtil.getTableRowCount(dataSource.getConnection(), tableName);
                    int fileNum = config.getLimitNum();
                    int singleLineLimit = (int) Math.ceil(totalRowCount / fileNum);
                    // ???????????????????????????????????????
                    consumer = new OrderByMergeExportConsumer(filePathPrefix,
                        config.getSeparator(), orderByColumnInfoList, orderedQueues, finishedList, singleLineLimit);
                    break;
                case DEFAULT:
                    consumer = new OrderByMergeExportConsumer(filePathPrefix,
                        config.getSeparator(), orderByColumnInfoList, orderedQueues, finishedList, 0);
                    break;
                default:
                    throw new RuntimeException("Unsupported export exception");
                }
                try {
                    consumer.consume();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                executor.shutdown();
                logger.info("?????? {} ????????????", tableName);
            } catch (DatabaseException | SQLException e) {
                e.printStackTrace();
                logger.error(e.getMessage());
            }
        }

    }

    /**
     * ??????order by?????????????????????
     * ??????DB????????????
     * TODO ?????????????????????
     */
    private void handleExportWithOrderByFromDb() {
        for (String tableName : command.getTableNames()) {
            try {
                TableFieldMetaInfo tableFieldMetaInfo = DbUtil.getTableFieldMetaInfo(dataSource.getConnection(),
                    getSchemaName(), tableName);
                DirectOrderExportWorker directOrderByExportWorker = ExportWorkerFactory
                    .buildDirectOrderExportWorker(dataSource, tableFieldMetaInfo, command, tableName);
                // ?????????????????????
                directOrderByExportWorker.exportSerially();
                logger.info("?????? {} ????????????", tableName);
            } catch (DatabaseException | SQLException e) {
                e.printStackTrace();
                logger.error(e.getMessage());
            }
        }
    }

    /**
     * ?????????????????????????????????
     */
    private void handleExportWithOrderByParallelMerge() {
        for (String tableName : command.getTableNames()) {
            List<TableTopology> topologyList;
            ExportConfig config = command.getExportConfig();
            List<FieldMetaInfo> orderByColumnInfoList;
            try {
                String filePathPrefix = FileUtil.getFilePathPrefix(config.getPath(),
                    config.getFilenamePrefix(), tableName);
                topologyList = DbUtil.getTopology(dataSource.getConnection(), tableName);
                TableFieldMetaInfo tableFieldMetaInfo = DbUtil.getTableFieldMetaInfo(dataSource.getConnection(),
                    getSchemaName(),tableName);
                orderByColumnInfoList = DbUtil.getFieldMetaInfoListByColNames(dataSource.getConnection(), getSchemaName(),
                    tableName, config.getOrderByColumnNameList());
                // ?????????
                final int shardSize = topologyList.size();
                ExecutorService executor = MyThreadPool.createExecutorWithEnsure(APP_NAME, shardSize);
                LocalOrderByExportProducer orderByExportProducer;
                LinkedList[] orderedLists = new LinkedList[shardSize];
                CountDownLatch countDownLatch = new CountDownLatch(shardSize);
                for (int i = 0; i < shardSize; i++) {
                    orderedLists[i] = new LinkedList<ParallelOrderByExportEvent>();
                    orderByExportProducer = new LocalOrderByExportProducer(dataSource, topologyList.get(i),
                        tableFieldMetaInfo, orderedLists[i], config.getOrderByColumnNameList(),
                        countDownLatch);
                    executor.submit(orderByExportProducer);
                }
                ParallelMergeExportConsumer consumer;
                switch (config.getExportWay()) {
                case MAX_LINE_NUM_IN_SINGLE_FILE:
                    consumer = new ParallelMergeExportConsumer(filePathPrefix,
                        config.getSeparator(), orderByColumnInfoList, orderedLists, config.getLimitNum());
                    break;
                case FIXED_FILE_NUM:
                    // ???????????????????????? ????????????????????????
                    double totalRowCount = DbUtil.getTableRowCount(dataSource.getConnection(), tableName);
                    int fileNum = config.getLimitNum();
                    int singleLineLimit = (int) Math.ceil(totalRowCount / fileNum);
                    // ???????????????????????????????????????
                    consumer = new ParallelMergeExportConsumer(filePathPrefix,
                        config.getSeparator(), orderByColumnInfoList, orderedLists, singleLineLimit);
                    break;
                case DEFAULT:
                    consumer = new ParallelMergeExportConsumer(filePathPrefix,
                        config.getSeparator(), orderByColumnInfoList, orderedLists, 0);
                    break;
                default:
                    throw new RuntimeException("Unsupported export exception");
                }
                try {
                    // ??????????????????????????????buffer?????????
                    countDownLatch.await();
                    consumer.consume();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                executor.shutdown();
                logger.info("?????? {} ????????????", tableName);
            } catch (DatabaseException | SQLException e) {
                e.printStackTrace();
                logger.error(e.getMessage());
            }
        }
    }
}
