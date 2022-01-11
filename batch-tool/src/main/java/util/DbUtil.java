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

package util;

import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.util.JdbcUtils;
import exception.DatabaseException;
import model.db.FieldMetaInfo;
import model.db.PartitionKey;
import model.db.PrimaryKey;
import model.db.TableFieldMetaInfo;
import model.db.TableTopology;
import org.apache.commons.lang.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DbUtil {

    private static final String PK_INDEX_SQL_PATTERN =
        "SELECT ORDINAL_POSITION,COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA='%s' and TABLE_NAME='%s' and COLUMN_KEY='PRI';";

    private static final String PK_INFO_SQL_PATTERN =
        "SELECT COLUMN_NAME,DATA_TYPE,ORDINAL_POSITION from INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA='%s' and TABLE_NAME='%s' and COLUMN_KEY='PRI';";

    private static final String FIELD_INFO_SQL_PATTERN =
        "SELECT COLUMN_NAME,DATA_TYPE,ORDINAL_POSITION from INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='%s' and TABLE_NAME='%s'\n"
            + "ORDER BY ORDINAL_POSITION;";

    private static final String SINGLE_FIELD_INFO_SQL_PATTERN =
        "SELECT DATA_TYPE,ORDINAL_POSITION from INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA='%s' and TABLE_NAME='%s' and COLUMN_NAME='%s';";

    private static final String MULTI_FIELD_INFO_SQL_PATTERN =
        "SELECT COLUMN_NAME,DATA_TYPE,ORDINAL_POSITION from INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA='%s' and TABLE_NAME='%s' and COLUMN_NAME in (%s);";

    private static final String PARTITION_KEY_SQL_PATTERN = "SHOW RULE FROM %s;";

    private static final String ROW_COUNT_SQL_PATTERN = "SELECT COUNT(*) FROM %s;";

    private static final String PARTITION_KEY_INFO_SQL_PATTERN =
        "SELECT DATA_TYPE,ORDINAL_POSITION from INFORMATION_SCHEMA.COLUMNS WHERE "
            + "TABLE_SCHEMA='%s' and TABLE_NAME='%s' and COLUMN_NAME = '%s'";

    /**
     * 获取数据表
     * 分库分表的拓扑结构
     *
     * @param tableName 数据表名
     */
    public static List<TableTopology> getTopology(Connection conn, String tableName) throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String sql = "SHOW TOPOLOGY FROM " + tableName;
        List<TableTopology> topologyList = new ArrayList<>();
        try {
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(sql);

            while (resultSet.next()) {
                topologyList.add(new TableTopology(
                    resultSet.getString("GROUP_NAME"),
                    resultSet.getString("TABLE_NAME")));
            }
            return topologyList;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get topology of table " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 获取数据表主键
     * 仅单个主键
     */
    public static PrimaryKey getPkIndex(Connection conn, String schemaName, String tableName) throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String sql = String.format(PK_INDEX_SQL_PATTERN, schemaName, tableName);
        int index;
        String name;
        try {
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(sql);

            if (resultSet.next()) {
                index = resultSet.getInt(1);
                name = resultSet.getString(2);
                return new PrimaryKey(index, name);
            } else {
                throw new DatabaseException("Unable to get primary key of table " + tableName);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get primary key of table " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 考虑复合主键的情况
     */
    public static List<PrimaryKey> getPkList(Connection conn, String schemaName, String tableName)
        throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String sql = String.format(PK_INDEX_SQL_PATTERN, schemaName, tableName);
        int ordinalPos;
        String name;
        List<PrimaryKey> pkList = new ArrayList<>();
        try {
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(sql);

            while (resultSet.next()) {
                ordinalPos = resultSet.getInt(1);
                name = resultSet.getString(2);
                pkList.add(new PrimaryKey(ordinalPos, name));
            }
            if (pkList.isEmpty()) {
                throw new DatabaseException("Unable to get primary key of table " + tableName);
            }
            return pkList;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get primary key of table " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 获取表字段信息用于更新
     * 只区分字符型和数值型
     */
    public static TableFieldMetaInfo getTableFieldMetaInfoForUpdate(Connection conn, String schemaName,
                                                                    String tableName)
        throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String pkIndexSql = String.format(PK_INDEX_SQL_PATTERN, schemaName, tableName);
        String metaInfoSql = String.format(FIELD_INFO_SQL_PATTERN, schemaName, tableName);
        int index;
        String name;
        FieldMetaInfo fieldMetaInfo;
        TableFieldMetaInfo tableFieldMetaInfo = new TableFieldMetaInfo();
        try {
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(pkIndexSql);
            if (resultSet.next()) {
                index = resultSet.getInt(1);
                name = resultSet.getString(2);
                tableFieldMetaInfo.setPrimaryKey(new PrimaryKey(index, name));
            } else {
                throw new DatabaseException("Unable to get primary key of table " + tableName);
            }

            // 开始获取字段信息
            List<FieldMetaInfo> fieldMetaInfoList = new ArrayList<>();
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            while (resultSet.next()) {
                fieldMetaInfo = new FieldMetaInfo();
                fieldMetaInfo.setType(resultSet.getString(2));
                if (fieldMetaInfo.getType() != FieldMetaInfo.Type.OTHER) {
                    // 只添加要求的两种类型
                    fieldMetaInfo.setName(resultSet.getString(1));
                    fieldMetaInfo.setIndex(resultSet.getInt(3) - 1);
                    fieldMetaInfoList.add(fieldMetaInfo);
                }
            }
            tableFieldMetaInfo.setFieldMetaInfoList(fieldMetaInfoList);
            return tableFieldMetaInfo;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get meta info of columns in " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 获取表字段信息用于replace更新
     * 所有字段
     */
    public static TableFieldMetaInfo getTableFieldMetaInfoForReplace(Connection conn, String schemaName,
                                                                     String tableName)
        throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String pkIndexSql = String.format(PK_INDEX_SQL_PATTERN, schemaName, tableName);
        String metaInfoSql = String.format(FIELD_INFO_SQL_PATTERN, schemaName, tableName);
        int index;
        String name;
        FieldMetaInfo fieldMetaInfo;
        TableFieldMetaInfo tableFieldMetaInfo = new TableFieldMetaInfo();
        try {
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(pkIndexSql);
            if (resultSet.next()) {
                index = resultSet.getInt(1);
                name = resultSet.getString(2);
                tableFieldMetaInfo.setPrimaryKey(new PrimaryKey(index, name));
            } else {
                throw new DatabaseException("Unable to get primary key of table " + tableName);
            }

            // 开始获取字段信息
            List<FieldMetaInfo> fieldMetaInfoList = new ArrayList<>();
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            while (resultSet.next()) {
                fieldMetaInfo = new FieldMetaInfo();
                fieldMetaInfo.setType(resultSet.getString(2));
                fieldMetaInfo.setName(resultSet.getString(1));
                fieldMetaInfo.setIndex(resultSet.getInt(3) - 1);
                fieldMetaInfoList.add(fieldMetaInfo);
            }
            tableFieldMetaInfo.setFieldMetaInfoList(fieldMetaInfoList);
            return tableFieldMetaInfo;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get meta info of columns in " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 获取表字段信息用于导入 以及 导出
     * 包括所有字段
     */
    public static TableFieldMetaInfo getTableFieldMetaInfo(Connection conn,
                                                           String schemaName,
                                                           String tableName)
        throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String metaInfoSql = String.format(FIELD_INFO_SQL_PATTERN, schemaName, tableName);
        FieldMetaInfo fieldMetaInfo;
        TableFieldMetaInfo tableFieldMetaInfo = new TableFieldMetaInfo();
        try {
            // 开始获取字段信息
            List<FieldMetaInfo> fieldMetaInfoList = new ArrayList<>();
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            while (resultSet.next()) {
                fieldMetaInfo = new FieldMetaInfo();
                fieldMetaInfo.setName(resultSet.getString(1));
                fieldMetaInfo.setType(resultSet.getString(2));
                fieldMetaInfo.setIndex(resultSet.getInt(3) - 1);
                fieldMetaInfoList.add(fieldMetaInfo);
            }
            tableFieldMetaInfo.setFieldMetaInfoList(fieldMetaInfoList);
            return tableFieldMetaInfo;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get meta info of columns in " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 获取表对应的划分键
     */
    public static PartitionKey getPartitionKey(Connection conn, String schemaName, String tableName)
        throws DatabaseException {
        String partitionInfo = String.format(PARTITION_KEY_SQL_PATTERN, tableName);
        Statement stmt = null;
        ResultSet resultSet = null;
        String keyName;
        int dbPartitionCount, tbPartitionCount;
        PartitionKey partitionKey = new PartitionKey();
        try {
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(partitionInfo);
            if (resultSet.next()) {
                keyName = resultSet.getString("DB_PARTITION_KEY");
                dbPartitionCount = resultSet.getInt("DB_PARTITION_COUNT");
                tbPartitionCount = resultSet.getInt("TB_PARTITION_COUNT");
                partitionKey.setDbPartitionCount(dbPartitionCount);
                partitionKey.setTbPartitionCount(tbPartitionCount);
            } else {
                throw new DatabaseException("Unable to get partition key of " + tableName);
            }

            // 根据key名字获取字段顺序、类型信息
            String keyInfoSql = String.format(PARTITION_KEY_INFO_SQL_PATTERN,
                schemaName, tableName, keyName);
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(keyInfoSql);
            if (resultSet.next()) {
                FieldMetaInfo fieldMetaInfo = new FieldMetaInfo();
                fieldMetaInfo.setName(keyName);
                fieldMetaInfo.setType(resultSet.getString(1));
                fieldMetaInfo.setIndex(resultSet.getInt(2) - 1);
                partitionKey.setFieldMetaInfo(fieldMetaInfo);
            } else {
                throw new DatabaseException("Unable to get partition key of " + tableName);
            }
            return partitionKey;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get partition key of " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    public static int getPartitionIndex(String value, PartitionKey partitionKey) {
        int partitionSize = partitionKey.getPartitionSize();
        switch (partitionKey.getFieldMetaInfo().getType()) {
        case STRING:
            return Math.abs(value.hashCode()) % partitionSize;
        case INT:
            return (int) (Math.abs(Long.parseLong(value)) % partitionSize);
        default:
            throw new UnsupportedOperationException("Unsupported partition key type!");
        }
    }

    public static FieldMetaInfo getPkMetaInfo(DruidPooledConnection conn, String schemaName,
                                              String tableName) throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String metaInfoSql = String.format(PK_INFO_SQL_PATTERN, schemaName, tableName);
        FieldMetaInfo fieldMetaInfo;
        try {
            // 开始获取字段信息
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            if (resultSet.next()) {
                fieldMetaInfo = new FieldMetaInfo();
                fieldMetaInfo.setName(resultSet.getString(1));
                fieldMetaInfo.setType(resultSet.getString(2));
                fieldMetaInfo.setIndex(resultSet.getInt(3) - 1);
            } else {
                throw new DatabaseException("Unable to get pk info of " + tableName);
            }
            return fieldMetaInfo;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get pk info of " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    public static FieldMetaInfo getFieldMetaInfo(DruidPooledConnection conn, String schemaName,
                                                 String tableName, String fieldName) throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String metaInfoSql = String.format(SINGLE_FIELD_INFO_SQL_PATTERN, schemaName, tableName, fieldName);
        FieldMetaInfo fieldMetaInfo;
        try {
            // 开始获取字段信息
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            if (resultSet.next()) {
                fieldMetaInfo = new FieldMetaInfo();
                fieldMetaInfo.setName(fieldName);
                fieldMetaInfo.setType(resultSet.getString(1));
                fieldMetaInfo.setIndex(resultSet.getInt(2) - 1);
            } else {
                throw new DatabaseException("Unable to get field info of " + fieldName);
            }
            return fieldMetaInfo;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Unable to get field info of " + fieldName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    /**
     * 拼接出主键的where条件
     */
    public static String formatPkConditions(List<PrimaryKey> pkList, String[] values) {
        String[] pkConditions = new String[pkList.size()];
        for (int i = 0; i < pkList.size(); i++) {
            pkConditions[i] = pkList.get(i).getName() + "='" + values[i] + "'";
        }
        return StringUtils.join(pkConditions, " AND ");
    }

    private static String getMultiFieldMetaInfoSql(String schemaName,
                                                   String tableName,
                                                   List<String> orderByColumnNameList) {
        List<String> columnConditionList = new ArrayList<>(orderByColumnNameList.size());
        for (String colName : orderByColumnNameList) {
            columnConditionList.add("'" + colName + "'");
        }
        return String.format(MULTI_FIELD_INFO_SQL_PATTERN,
            schemaName, tableName, StringUtils.join(columnConditionList, ","));
    }

    public static List<FieldMetaInfo> getFieldMetaInfoListByColNames(DruidPooledConnection conn,
                                                                     String schemaName,
                                                                     String tableName,
                                                                     List<String> orderByColumnNameList)
        throws DatabaseException {

        Statement stmt = null;
        ResultSet resultSet = null;
        String metaInfoSql = getMultiFieldMetaInfoSql(schemaName,
            tableName, orderByColumnNameList);
        List<FieldMetaInfo> fieldMetaInfoList = new ArrayList<>(orderByColumnNameList.size());
        FieldMetaInfo fieldMetaInfo;
        for (String colName : orderByColumnNameList) {
            fieldMetaInfo = new FieldMetaInfo();
            fieldMetaInfo.setName(colName);
            fieldMetaInfoList.add(fieldMetaInfo);
        }
        try {
            // 开始获取字段信息
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            String curColName;
            int foundColCount = 0;
            while (resultSet.next()) {
                curColName = resultSet.getString(1);
                // 排序字段不会很多 可以遍历查找
                for (int i = 0; i < fieldMetaInfoList.size(); i++) {
                    fieldMetaInfo = fieldMetaInfoList.get(i);
                    if (fieldMetaInfo.getName().equalsIgnoreCase(curColName)) {
                        foundColCount++;
                        fieldMetaInfo.setType(resultSet.getString(2));
                        fieldMetaInfo.setIndex(resultSet.getInt(3) - 1);
                    }
                }
            }
            if (foundColCount != orderByColumnNameList.size()) {
                throw new DatabaseException("Wrong order by columns" + orderByColumnNameList);
            }
            return fieldMetaInfoList;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Wrong order by columns" + orderByColumnNameList);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }

    public static long getTableRowCount(Connection conn, String tableName) throws DatabaseException {
        Statement stmt = null;
        ResultSet resultSet = null;
        String metaInfoSql = String.format(ROW_COUNT_SQL_PATTERN, tableName);
        try {
            // 开始获取字段信息
            stmt = conn.createStatement();
            resultSet = stmt.executeQuery(metaInfoSql);
            if (!resultSet.next()) {
                throw new DatabaseException("Cannot get row count of " + tableName);
            }
            return resultSet.getLong(1);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new DatabaseException("Cannot get row count of " + tableName);
        } finally {
            JdbcUtils.close(resultSet);
            JdbcUtils.close(stmt);
            JdbcUtils.close(conn);
        }
    }
}