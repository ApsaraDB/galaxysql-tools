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
import com.alibaba.druid.pool.DruidDataSource;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WorkerPool;
import datasource.DataSourceConfig;
import exception.DatabaseException;
import model.CyclicAtomicInteger;
import model.db.TableFieldMetaInfo;
import model.db.TableTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.DbUtil;
import worker.MyThreadPool;
import worker.MyWorkerPool;
import worker.export.CollectFragmentWorker;
import worker.export.DirectExportWorker;
import worker.export.ExportConsumer;
import worker.export.ExportEvent;
import worker.export.ExportProducer;
import worker.factory.ExportWorkerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static model.config.ConfigConstant.APP_NAME;

public class ShardingExportExecutor extends BaseExportExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ShardingExportExecutor.class);

    private ExecutorService executor;

    public ShardingExportExecutor(DataSourceConfig dataSourceConfig,
                                  DruidDataSource druid,
                                  BaseOperateCommand baseCommand) {
        super(dataSourceConfig, druid, baseCommand);

    }

    @Override
    void exportData() {
        doExportWithSharding();
    }

    /**
     * 处理分库分表导出命令
     * TODO 重构执行逻辑
     */
    private void doExportWithSharding() {
        try {
            List<TableTopology> topologyList = DbUtil.getTopology(dataSource.getConnection(), command.getTableName());
            TableFieldMetaInfo tableFieldMetaInfo = DbUtil.getTableFieldMetaInfo(dataSource.getConnection(),
                getSchemaName(), command.getTableName());
            // 分片数
            final int shardSize = topologyList.size();
            int parallelism = config.getParallelism();
            parallelism = parallelism > 0 ? parallelism : shardSize;
            Semaphore permitted = new Semaphore(parallelism, true);

            ExecutorService executor = MyThreadPool.createExecutorWithEnsure(APP_NAME, shardSize);
            DirectExportWorker directExportWorker;
            CountDownLatch countDownLatch = new CountDownLatch(shardSize);
            switch (config.getExportWay()) {
            case MAX_LINE_NUM_IN_SINGLE_FILE:
            case DEFAULT:
                for (int i = 0; i < shardSize; i++) {
                    directExportWorker = ExportWorkerFactory.buildDefaultDirectExportWorker(dataSource,
                        topologyList.get(i), tableFieldMetaInfo,
                        command.getFilePathPrefix() + i, config);
                    directExportWorker.setCountDownLatch(countDownLatch);
                    directExportWorker.setPermitted(permitted);
                    executor.submit(directExportWorker);
                }
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break;
            case FIXED_FILE_NUM:
                shardingExportWithFixedFile(topologyList, tableFieldMetaInfo, shardSize,
                    executor, permitted, countDownLatch);
                break;
            default:
                throw new RuntimeException("Unsupported export exception: " + config.getExportWay());
            }
            executor.shutdown();
            logger.info("导出 {} 数据完成", command.getTableName());
        } catch (DatabaseException | SQLException e) {
            e.printStackTrace();
        }
    }

    private void shardingExportWithFixedFile(List<TableTopology> topologyList,
                                             TableFieldMetaInfo tableFieldMetaInfo,
                                             int shardSize,
                                             ExecutorService executor,
                                             Semaphore permitted,
                                             CountDownLatch countDownLatch) {
        // 初始化缓冲区等
        EventFactory<ExportEvent> factory = ExportEvent::new;
        RingBuffer<ExportEvent> ringBuffer = MyWorkerPool.createRingBuffer(factory);
        AtomicInteger emittedDataCounter = new AtomicInteger(0);
        // 消费者数量与文件数一致 生产者数量和shard数一致
        final int consumerCount = config.getLimitNum(), producerCount = shardSize;

        ExportConsumer[] consumers = new ExportConsumer[consumerCount];
        String[] filePaths = new String[consumerCount];
        for (int i = 0; i < consumers.length; i++) {
            filePaths[i] = command.getFilePathPrefix() + i;
            consumers[i] = new ExportConsumer(filePaths[i], emittedDataCounter,
                config.isWithHeader(),
                config.getSeparator().getBytes(),
                tableFieldMetaInfo, config.getCompressMode());
        }
        WorkerPool<ExportEvent> workerPool = MyWorkerPool.createWorkerPool(ringBuffer, consumers);
        workerPool.start(executor);

        ExecutorService producerExecutor = MyThreadPool.createExecutorWithEnsure("producer", producerCount);
        // 每一个生产者最后的碎片数据处理
        if (producerCount >= consumerCount * 2) {
            // 当生产者数量略大于消费者时 没必要轮询分配碎片
            for (TableTopology topology : topologyList) {
                ExportProducer producer = new ExportProducer(dataSource, topology,
                    tableFieldMetaInfo, ringBuffer, config.getSeparator(),
                    countDownLatch, emittedDataCounter, false, config.getQuoteEncloseMode());
                producer.setPermitted(permitted);
                producer.setWhereCondition(config.getWhereCondition());
                producerExecutor.submit(producer);
            }
            waitForFinish(countDownLatch, emittedDataCounter);
            workerPool.halt();
        } else {
            Queue<ExportEvent> fragmentQueue = new ArrayBlockingQueue<>(producerCount);
            // 将碎片放入缓冲队列
            for (TableTopology topology : topologyList) {
                ExportProducer producer = new ExportProducer(dataSource, topology,
                    tableFieldMetaInfo, ringBuffer, config.getSeparator(),
                    countDownLatch, emittedDataCounter,
                    true, config.getQuoteEncloseMode());
                producer.setWhereCondition(config.getWhereCondition());
                producer.setFragmentQueue(fragmentQueue);
                producer.setPermitted(permitted);
                producerExecutor.submit(producer);
            }
            CyclicAtomicInteger cyclicCounter = new CyclicAtomicInteger(consumerCount);
            // 待消费者消费结束
            waitForFinish(countDownLatch, emittedDataCounter);
            workerPool.halt();
            CountDownLatch fragmentCountLatch = new CountDownLatch(consumerCount);
            // 再将碎片一次分配给每个文件
            for (int i = 0; i < consumerCount; i++) {
                CollectFragmentWorker collectFragmentWorker = new CollectFragmentWorker(
                    fragmentQueue, filePaths, cyclicCounter, fragmentCountLatch);
                executor.submit(collectFragmentWorker);
            }
            try {
                fragmentCountLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        for (ExportConsumer consumer : consumers) {
            consumer.close();
        }
        producerExecutor.shutdown();
    }
}