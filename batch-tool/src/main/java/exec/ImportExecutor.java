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

package exec;

import cmd.BaseOperateCommand;
import cmd.ImportCommand;
import com.alibaba.druid.pool.DruidDataSource;
import datasource.DataSourceConfig;
import exception.DatabaseException;
import model.config.ConfigConstant;
import model.config.DdlMode;
import model.config.QuoteEncloseMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.DbUtil;
import worker.ddl.DdlImporter;
import worker.insert.DirectImportWorker;
import worker.insert.ImportConsumer;
import worker.insert.ProcessOnlyImportConsumer;
import worker.insert.ShardedImportConsumer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class ImportExecutor extends WriteDbExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ImportExecutor.class);

    private ImportCommand command;

    public ImportExecutor(DataSourceConfig dataSourceConfig,
                          DruidDataSource druid,
                          BaseOperateCommand baseCommand) {
        super(dataSourceConfig, druid, baseCommand);
    }

    @Override
    protected void setCommand(BaseOperateCommand baseCommand) {
        this.command = (ImportCommand) baseCommand;
    }

    @Override
    public void preCheck() {
        if (producerExecutionContext.getDdlMode() != DdlMode.NO_DDL) {
            if (command.isDbOperation()) {
                checkDbNotExist(command.getDbName());
            } else {
                checkTableNotExist(command.getTableNames());
            }
        } else {
            if (command.isDbOperation()) {
                try (Connection conn = dataSource.getConnection()) {
                    this.tableNames = DbUtil.getAllTablesInDb(conn, command.getDbName());
                } catch (SQLException | DatabaseException e) {
                    throw new RuntimeException(e);
                }
            } else {
                checkTableExists(command.getTableNames());
                this.tableNames = command.getTableNames();
            }
        }
    }

    private void checkDbNotExist(String dbName) {
        if (ConfigConstant.DEFAULT_SCHEMA_NAME.equalsIgnoreCase(dbName)) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            if (DbUtil.checkDatabaseExists(conn, dbName)) {
               throw new RuntimeException(String.format("Database [%s] already exists, cannot import with ddl",
                   dbName));
            }
        } catch (SQLException | DatabaseException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    private void checkTableNotExist(List<String> tableNames) {
        for (String tableName : tableNames) {
            try (Connection conn = dataSource.getConnection()) {
                if (DbUtil.checkTableExists(conn, tableName)) {
                    throw new RuntimeException(String.format("Table [%s] already exists, cannot import with ddl",
                        tableNames));
                }
            } catch (SQLException | DatabaseException e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    @Override
    public void execute() {
        switch (producerExecutionContext.getDdlMode()) {
        case WITH_DDL:
            handleDDL();
            break;
        case DDL_ONLY:
            handleDDL();
            return;
        case NO_DDL:
            break;
        default:
            throw new UnsupportedOperationException("DDL mode is not supported: " +
                producerExecutionContext.getDdlMode());
        }
        configureFieldMetaInfo();
        for (String tableName : tableNames) {
            if (producerExecutionContext.isSingleThread()
                && consumerExecutionContext.isSingleThread())  {
                // ??????????????????insert??????
                doSingleThreadImport(tableName);
            } else {
                if (command.isShardingEnabled()) {
                    doShardingImport(tableName);
                } else {
                    doDefaultImport(tableName);
                }
            }

            if (producerExecutionContext.getException() != null) {
                logger.error("??????????????? {} ?????????{}", tableName,
                    producerExecutionContext.getException().getMessage());
                return;
            }
            if (consumerExecutionContext.getException() != null) {
                logger.error("??????????????? {} ?????????{}", tableName,
                    consumerExecutionContext.getException().getMessage());
                return;
            }
            logger.info("??????????????? {} ??????", tableName);
        }
    }

    /**
     * ??????????????????????????????
     */
    private void handleDDL() {
        DdlImporter ddlImporter;
        if (command.isDbOperation()) {
            if (producerExecutionContext.getFileLineRecordList().size() != 1) {
                throw new UnsupportedOperationException("Import database DDL only support one ddl file now!");
            }
            ddlImporter = new DdlImporter(producerExecutionContext.getFileLineRecordList()
                .get(0).getFilePath(), dataSource);
        } else {
            ddlImporter = new DdlImporter(command.getTableNames(), dataSource);
        }
        ddlImporter.doImportSync();
    }

    private void doSingleThreadImport(String tableName) {
        DirectImportWorker directImportWorker = new DirectImportWorker(dataSource, tableName,
            producerExecutionContext, consumerExecutionContext);
        Thread importThread = new Thread(directImportWorker);
        importThread.start();
        try {
            importThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void doDefaultImport(String tableName) {
        if (consumerExecutionContext.isReadProcessFileOnly()) {
            // ???????????????????????????
            configureCommonContextAndRun(ProcessOnlyImportConsumer.class,
                producerExecutionContext, consumerExecutionContext, tableName);
        } else {
            configureCommonContextAndRun(ImportConsumer.class,
                producerExecutionContext, consumerExecutionContext, tableName,
                useBlockReader());
        }
    }

    private void doShardingImport(String tableName) {
        configurePartitionKey();
        configureTopology();

        configureCommonContextAndRun(ShardedImportConsumer.class,
            producerExecutionContext, consumerExecutionContext, tableName,
            useBlockReader());
    }

    private boolean useBlockReader() {
        if (producerExecutionContext.getQuoteEncloseMode() == QuoteEncloseMode.FORCE) {
            return false;
        }
        if (!producerExecutionContext.getEncryptionConfig().getEncryptionMode().isSupportStreamingBit()) {
            return false;
        }
        if (!producerExecutionContext.getFileFormat().isSupportBlock()) {
            return false;
        }
        return true;
    }
}
