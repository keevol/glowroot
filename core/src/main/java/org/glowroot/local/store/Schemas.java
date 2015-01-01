/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.Checkers.castUntainted;

class Schemas {

    private static final Logger logger = LoggerFactory.getLogger(Schemas.class);

    private static final Map<Integer, String> sqlTypeNames = Maps.newHashMap();

    static {
        sqlTypeNames.put(Types.VARCHAR, "varchar");
        sqlTypeNames.put(Types.BIGINT, "bigint");
        sqlTypeNames.put(Types.BOOLEAN, "boolean");
        sqlTypeNames.put(Types.CLOB, "clob");
        sqlTypeNames.put(Types.DOUBLE, "double");
    }

    private Schemas() {}

    static void syncTable(@Untainted String tableName, ImmutableList<Column> columns,
            Connection connection) throws SQLException {
        if (!tableExists(tableName, connection)) {
            createTable(tableName, columns, connection);
        } else if (tableNeedsUpgrade(tableName, columns, connection)) {
            logger.warn("upgrading table {}, which unfortunately at this point just means"
                    + " dropping and re-create the table (losing existing data)", tableName);
            execute("drop table " + tableName, connection);
            createTable(tableName, columns, connection);
        }
    }

    static void syncIndexes(@Untainted String tableName, ImmutableList<Index> indexes,
            Connection connection) throws SQLException {
        ImmutableSet<Index> desiredIndexes = ImmutableSet.copyOf(indexes);
        Set<Index> existingIndexes = getIndexes(tableName, connection);
        for (Index index : Sets.difference(existingIndexes, desiredIndexes)) {
            execute("drop index " + index.name(), connection);
        }
        for (Index index : Sets.difference(desiredIndexes, existingIndexes)) {
            createIndex(tableName, index, connection);
        }
        // test the logic
        existingIndexes = getIndexes(tableName, connection);
        if (!existingIndexes.equals(desiredIndexes)) {
            logger.error("the logic in syncIndexes() needs fixing");
        }
    }

    static boolean tableExists(String tableName, Connection connection) throws SQLException {
        logger.debug("tableExists(): tableName={}", tableName);
        ResultSet resultSet = connection.getMetaData().getTables(null, null,
                tableName.toUpperCase(Locale.ENGLISH), null);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            return resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    static ImmutableList<Column> getColumns(String tableName, Connection connection)
            throws SQLException {
        List<Column> columns = Lists.newArrayList();
        ResultSet resultSet = connection.getMetaData().getColumns(null, null,
                tableName.toUpperCase(Locale.ENGLISH), null);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            while (resultSet.next()) {
                String columnName = checkNotNull(resultSet.getString("COLUMN_NAME"));
                int columnType = resultSet.getInt("DATA_TYPE");
                columns.add(ImmutableColumn.of(columnName.toLowerCase(Locale.ENGLISH), columnType));
            }
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
        return ImmutableList.copyOf(columns);
    }

    private static void createTable(@Untainted String tableName, ImmutableList<Column> columns,
            Connection connection) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("create table ");
        sql.append(tableName);
        sql.append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String sqlTypeName = sqlTypeNames.get(columns.get(i).type());
            if (sqlTypeName == null) {
                throw new SQLException("Unexpected sql type '" + columns.get(i).type());
            }
            sql.append(columns.get(i).name());
            sql.append(" ");
            sql.append(sqlTypeName);
            if (columns.get(i) instanceof PrimaryKeyColumn) {
                sql.append(" primary key");
            }
        }
        sql.append(")");
        execute(castUntainted(sql.toString()), connection);
        if (tableNeedsUpgrade(tableName, columns, connection)) {
            logger.warn("table {} thinks it still needs to be upgraded, even after it was just"
                    + "upgraded", tableName);
        }
    }

    private static boolean tableNeedsUpgrade(String tableName, ImmutableList<Column> columns,
            Connection connection) throws SQLException {
        if (primaryKeyNeedsUpgrade(tableName, Iterables.filter(columns, PrimaryKeyColumn.class),
                connection)) {
            return true;
        }
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, Column> remaining = new TreeMap<String, Column>(String.CASE_INSENSITIVE_ORDER);
        for (Column column : columns) {
            remaining.put(column.name(), column);
        }
        ResultSet resultSet = connection.getMetaData().getColumns(null, null,
                tableName.toUpperCase(Locale.ENGLISH), null);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            while (resultSet.next()) {
                Column column = remaining.remove(resultSet.getString("COLUMN_NAME"));
                if (column == null) {
                    return true;
                }
                if (column.type() != resultSet.getInt("DATA_TYPE")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
        return !remaining.isEmpty();
    }

    private static boolean primaryKeyNeedsUpgrade(String tableName,
            Iterable<PrimaryKeyColumn> primaryKeyColumns, Connection connection)
            throws SQLException {
        ResultSet resultSet = connection.getMetaData().getPrimaryKeys(null, null,
                tableName.toUpperCase(Locale.ENGLISH));
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            for (PrimaryKeyColumn primaryKeyColumn : primaryKeyColumns) {
                if (!resultSet.next() || !primaryKeyColumn.name().equalsIgnoreCase(
                        resultSet.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
            // not ok to have extra columns on primary key
            return resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    @VisibleForTesting
    static ImmutableSet<Index> getIndexes(String tableName, Connection connection)
            throws SQLException {
        ListMultimap</*@Untainted*/String, /*@Untainted*/String> indexColumns =
                ArrayListMultimap.create();
        ResultSet resultSet = connection.getMetaData().getIndexInfo(null, null,
                tableName.toUpperCase(Locale.ENGLISH), false, false);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            while (resultSet.next()) {
                String indexName = checkNotNull(resultSet.getString("INDEX_NAME"));
                String columnName = checkNotNull(resultSet.getString("COLUMN_NAME"));
                // hack-ish to skip over primary key constraints which seem to be always
                // prefixed in H2 by PRIMARY_KEY_
                if (!indexName.startsWith("PRIMARY_KEY_")) {
                    indexColumns.put(castUntainted(indexName), castUntainted(columnName));
                }
            }
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
        ImmutableSet.Builder<Index> indexes = ImmutableSet.builder();
        for (Entry</*@Untainted*/String, Collection</*@Untainted*/String>> entry : indexColumns
                .asMap().entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ENGLISH);
            List<String> columns = Lists.newArrayList();
            for (String column : entry.getValue()) {
                columns.add(column.toLowerCase(Locale.ENGLISH));
            }
            indexes.add(ImmutableIndex.of(name, columns));
        }
        return indexes.build();
    }

    private static void createIndex(String tableName, Index index, Connection connection)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("create index ");
        sql.append(index.name());
        sql.append(" on ");
        sql.append(tableName);
        sql.append(" (");
        for (int i = 0; i < index.columns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(index.columns().get(i));
        }
        sql.append(")");
        execute(castUntainted(sql.toString()), connection);
    }

    private static void execute(@Untainted String sql, Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute(sql);
        } finally {
            statement.close();
        }
    }

    @Value.Immutable
    abstract static class Column {
        @Value.Parameter
        abstract String name();
        @Value.Parameter
        abstract int type();
    }

    @Value.Immutable
    abstract static class PrimaryKeyColumn extends Column {}

    @Value.Immutable
    abstract static class Index {
        @Value.Parameter
        abstract @Untainted String name();
        @Value.Parameter
        abstract List</*@Untainted*/String> columns();
    }
}
