/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.flink.action.Action;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.sink.TableFilter;
import org.apache.paimon.flink.sink.cdc.EventParser;
import org.apache.paimon.flink.sink.cdc.FlinkCdcSyncDatabaseSinkBuilder;
import org.apache.paimon.flink.sink.cdc.NewTableSchemaBuilder;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecordEventParser;
import org.apache.paimon.table.FileStoreTable;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.apache.paimon.flink.action.MultiTablesSinkMode.COMBINED;
import static org.apache.paimon.flink.action.cdc.ComputedColumnUtils.buildComputedColumns;

/** Base {@link Action} for synchronizing into one Paimon database. */
public abstract class SyncDatabaseActionBase extends SynchronizationActionBase {

    protected boolean eagerInit = false;
    protected boolean mergeShards = true;
    protected MultiTablesSinkMode mode = COMBINED;
    protected String tablePrefix = "";
    protected String tableSuffix = "";
    protected Map<String, String> tableMapping = new HashMap<>();
    protected Map<String, String> dbPrefix = new HashMap<>();
    protected Map<String, String> dbSuffix = new HashMap<>();
    protected String includingTables = ".*";
    protected List<String> partitionKeys = new ArrayList<>();
    protected List<String> primaryKeys = new ArrayList<>();
    protected List<ComputedColumn> computedColumns = new ArrayList<>();
    @Nullable protected String excludingTables;
    protected String includingDbs = ".*";
    @Nullable protected String excludingDbs;
    protected List<FileStoreTable> tables = new ArrayList<>();
    protected Map<String, List<String>> partitionKeyMultiple = new HashMap<>();

    public SyncDatabaseActionBase(
            String database,
            Map<String, String> catalogConfig,
            Map<String, String> cdcSourceConfig,
            SyncJobHandler.SourceType sourceType) {
        super(
                database,
                catalogConfig,
                cdcSourceConfig,
                new SyncJobHandler(sourceType, cdcSourceConfig, database));
    }

    public SyncDatabaseActionBase mergeShards(boolean mergeShards) {
        this.mergeShards = mergeShards;
        return this;
    }

    public SyncDatabaseActionBase eagerInit(boolean eagerInit) {
        this.eagerInit = eagerInit;
        return this;
    }

    public SyncDatabaseActionBase withMode(MultiTablesSinkMode mode) {
        this.mode = mode;
        return this;
    }

    public SyncDatabaseActionBase withTablePrefix(@Nullable String tablePrefix) {
        if (tablePrefix != null) {
            this.tablePrefix = tablePrefix;
        }
        return this;
    }

    public SyncDatabaseActionBase withTableSuffix(@Nullable String tableSuffix) {
        if (tableSuffix != null) {
            this.tableSuffix = tableSuffix;
        }
        return this;
    }

    public SyncDatabaseActionBase withDbPrefix(Map<String, String> dbPrefix) {
        if (dbPrefix != null) {
            this.dbPrefix =
                    dbPrefix.entrySet().stream()
                            .collect(
                                    HashMap::new,
                                    (m, e) -> m.put(e.getKey().toLowerCase(), e.getValue()),
                                    HashMap::putAll);
        }
        return this;
    }

    public SyncDatabaseActionBase withDbSuffix(Map<String, String> dbSuffix) {
        if (dbSuffix != null) {
            this.dbSuffix =
                    dbSuffix.entrySet().stream()
                            .collect(
                                    HashMap::new,
                                    (m, e) -> m.put(e.getKey().toLowerCase(), e.getValue()),
                                    HashMap::putAll);
        }
        return this;
    }

    public SyncDatabaseActionBase withTableMapping(Map<String, String> tableMapping) {
        if (tableMapping != null) {
            this.tableMapping = tableMapping;
        }
        return this;
    }

    public SyncDatabaseActionBase includingTables(@Nullable String includingTables) {
        if (includingTables != null) {
            this.includingTables = includingTables;
        }
        return this;
    }

    public SyncDatabaseActionBase excludingTables(@Nullable String excludingTables) {
        this.excludingTables = excludingTables;
        return this;
    }

    public SyncDatabaseActionBase includingDbs(@Nullable String includingDbs) {
        if (includingDbs != null) {
            this.includingDbs = includingDbs;
        }
        return this;
    }

    public SyncDatabaseActionBase excludingDbs(@Nullable String excludingDbs) {
        this.excludingDbs = excludingDbs;
        return this;
    }

    public SyncDatabaseActionBase withPartitionKeys(String... partitionKeys) {
        this.partitionKeys.addAll(Arrays.asList(partitionKeys));
        return this;
    }

    public SyncDatabaseActionBase withPrimaryKeys(String... primaryKeys) {
        this.primaryKeys.addAll(Arrays.asList(primaryKeys));
        return this;
    }

    public SyncDatabaseActionBase withComputedColumnArgs(List<String> computedColumnArgs) {
        this.computedColumns = buildComputedColumns(computedColumnArgs, Collections.emptyList());
        return this;
    }

    @Override
    protected FlatMapFunction<CdcSourceRecord, RichCdcMultiplexRecord> recordParse() {
        return syncJobHandler.provideRecordParser(
                this.computedColumns, typeMapping, metadataConverters);
    }

    public SyncDatabaseActionBase withPartitionKeyMultiple(
            Map<String, List<String>> partitionKeyMultiple) {
        if (partitionKeyMultiple != null) {
            this.partitionKeyMultiple = partitionKeyMultiple;
        }
        return this;
    }

    @Override
    protected EventParser.Factory<RichCdcMultiplexRecord> buildEventParserFactory() {
        NewTableSchemaBuilder schemaBuilder =
                new NewTableSchemaBuilder(
                        tableConfig,
                        caseSensitive,
                        partitionKeys,
                        primaryKeys,
                        requirePrimaryKeys(),
                        syncPKeysFromSourceSchema,
                        partitionKeyMultiple,
                        metadataConverters);
        Pattern tblIncludingPattern = Pattern.compile(includingTables);
        Pattern tblExcludingPattern =
                excludingTables == null ? null : Pattern.compile(excludingTables);
        Pattern dbIncludingPattern = Pattern.compile(includingDbs);
        Pattern dbExcludingPattern = excludingDbs == null ? null : Pattern.compile(excludingDbs);
        TableNameConverter tableNameConverter =
                new TableNameConverter(
                        caseSensitive,
                        mergeShards,
                        dbPrefix,
                        dbSuffix,
                        tablePrefix,
                        tableSuffix,
                        tableMapping);
        Set<String> createdTables;
        try {
            createdTables = new HashSet<>(catalog.listTables(database));
        } catch (Catalog.DatabaseNotExistException e) {
            throw new RuntimeException(e);
        }
        return () ->
                new RichCdcMultiplexRecordEventParser(
                        schemaBuilder,
                        tblIncludingPattern,
                        tblExcludingPattern,
                        dbIncludingPattern,
                        dbExcludingPattern,
                        tableNameConverter,
                        createdTables);
    }

    protected abstract boolean requirePrimaryKeys();

    @Override
    protected void buildSink(
            DataStream<RichCdcMultiplexRecord> input,
            EventParser.Factory<RichCdcMultiplexRecord> parserFactory) {

        List<String> whiteList = new ArrayList<>(tableMapping.values());
        List<String> prefixList = new ArrayList<>(dbPrefix.values());
        prefixList.add(tablePrefix);
        List<String> suffixList = new ArrayList<>(dbSuffix.values());
        suffixList.add(tableSuffix);

        new FlinkCdcSyncDatabaseSinkBuilder<RichCdcMultiplexRecord>()
                .withInput(input)
                .withParserFactory(parserFactory)
                .withCatalogLoader(catalogLoader())
                .withTypeMapping(typeMapping)
                .withDatabase(database)
                .withTables(tables)
                .withMode(mode)
                .withTableOptions(tableConfig)
                .withEagerInit(eagerInit)
                .withTableFilter(
                        new TableFilter(
                                database,
                                whiteList,
                                prefixList,
                                suffixList,
                                includingTables,
                                excludingTables))
                .build();
    }
}
