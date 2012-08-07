/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency.checking.full;

import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.consistency.checking.CheckDecorator;
import org.neo4j.consistency.report.ConsistencyLogger;
import org.neo4j.consistency.report.ConsistencySummaryStatistics;
import org.neo4j.consistency.report.MessageConsistencyLogger;
import org.neo4j.consistency.store.CacheSmallStoresRecordAccess;
import org.neo4j.consistency.store.DiffRecordAccess;
import org.neo4j.consistency.store.DirectRecordAccess;
import org.neo4j.graphdb.factory.GraphDatabaseSetting;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.kernel.DefaultFileSystemAbstraction;
import org.neo4j.kernel.DefaultIdGeneratorFactory;
import org.neo4j.kernel.DefaultLastCommittedTxIdSetter;
import org.neo4j.kernel.DefaultTxHook;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.nioneo.store.AbstractBaseRecord;
import org.neo4j.kernel.impl.nioneo.store.DefaultWindowPoolFactory;
import org.neo4j.kernel.impl.nioneo.store.DynamicRecord;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyIndexRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.RecordStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeRecord;
import org.neo4j.kernel.impl.nioneo.store.StoreAccess;
import org.neo4j.kernel.impl.nioneo.store.StoreFactory;
import org.neo4j.consistency.store.windowpool.ScanResistantWindowPoolFactory;
import org.neo4j.kernel.impl.nioneo.store.windowpool.WindowPoolFactory;
import org.neo4j.kernel.impl.util.StringLogger;

import static org.neo4j.consistency.checking.full.FilteringStoreProcessor.ARRAYS_ONLY;
import static org.neo4j.consistency.checking.full.FilteringStoreProcessor.EVERYTHING;
import static org.neo4j.consistency.checking.full.FilteringStoreProcessor.NODES_ONLY;
import static org.neo4j.consistency.checking.full.FilteringStoreProcessor.PROPERTIES_ONLY;
import static org.neo4j.consistency.checking.full.FilteringStoreProcessor.RELATIONSHIPS_ONLY;
import static org.neo4j.consistency.checking.full.FilteringStoreProcessor.STRINGS_ONLY;
import static org.neo4j.consistency.checking.full.TaskExecutionOrder.MULTI_PASS;
import static org.neo4j.consistency.checking.full.TaskExecutionOrder.MULTI_THREADED;
import static org.neo4j.consistency.checking.full.TaskExecutionOrder.SINGLE_THREADED;

public class FullCheck
{
    /** Defaults to false due to the way Boolean.parseBoolean(null) works. */
    public static final GraphDatabaseSetting<Boolean> consistency_check_property_owners =
            new GraphDatabaseSetting.BooleanSetting( "consistency_check_property_owners" );
    /** Defaults to false due to the way Boolean.parseBoolean(null) works. */
    public static final GraphDatabaseSetting<Boolean> consistency_check_single_threaded =
            new GraphDatabaseSetting.BooleanSetting( "consistency_check_single_threaded" );
    /** Defaults to false due to the way Boolean.parseBoolean(null) works. */
    public static final GraphDatabaseSetting<Boolean> consistency_check_multiple_passes =
            new GraphDatabaseSetting.BooleanSetting( "consistency_check_multiple_passes" );
    /** Defaults to false due to the way Boolean.parseBoolean(null) works. */
    public static final GraphDatabaseSetting<Boolean> use_scan_resistant_window_pools =
            new GraphDatabaseSetting.BooleanSetting( "use_scan_resistant_window_pools" );

    private final boolean checkPropertyOwners;
    private final TaskExecutionOrder order;
    private final ProgressMonitorFactory progressFactory;

    public FullCheck( boolean checkPropertyOwners, ProgressMonitorFactory progressFactory )
    {
        this(checkPropertyOwners, MULTI_THREADED, progressFactory);
    }

    public FullCheck( boolean checkPropertyOwners, TaskExecutionOrder order,
               ProgressMonitorFactory progressFactory )
    {
        this.checkPropertyOwners = checkPropertyOwners;
        this.order = order;
        this.progressFactory = progressFactory;
    }

    public ConsistencySummaryStatistics execute( StoreAccess store, StringLogger logger ) throws ConsistencyCheckIncompleteException
    {
        ConsistencySummaryStatistics summary = new ConsistencySummaryStatistics();
        OwnerCheck ownerCheck = new OwnerCheck( checkPropertyOwners );
        execute( store, ownerCheck, recordAccess( store ), new MessageConsistencyLogger( logger ), summary );
        ownerCheck.scanForOrphanChains( progressFactory );
        if ( !summary.isConsistent() )
        {
            logger.logMessage( "Inconsistencies found: " + summary );
        }
        return summary;
    }

    void execute( StoreAccess store, CheckDecorator decorator, DiffRecordAccess recordAccess,
                  ConsistencyLogger logger, ConsistencySummaryStatistics summary )
            throws ConsistencyCheckIncompleteException
    {
        FilteringStoreProcessor.Factory processorFactory = new FilteringStoreProcessor.Factory(
                decorator, logger, recordAccess, summary );
        StoreProcessor processEverything = processorFactory.create( EVERYTHING );

        ProgressMonitorFactory.MultiPartBuilder progress = progressFactory.multipleParts( "Full consistency check" );
        List<StoreProcessorTask> tasks = new ArrayList<StoreProcessorTask>( 9 );

        tasks.add( new StoreProcessorTask<NodeRecord>( store.getNodeStore(), progress,
                                                       processorFactory.createAll( PROPERTIES_ONLY,
                                                                                   RELATIONSHIPS_ONLY ) ) );
        tasks.add( new StoreProcessorTask<RelationshipRecord>( store.getRelationshipStore(), progress,
                                                               processorFactory.createAll( NODES_ONLY,
                                                                                           PROPERTIES_ONLY,
                                                                                           RELATIONSHIPS_ONLY ) ) );
        tasks.add( new StoreProcessorTask<PropertyRecord>( store.getPropertyStore(), progress,
                                                           processorFactory.createAll( PROPERTIES_ONLY,
                                                                                       STRINGS_ONLY,
                                                                                       ARRAYS_ONLY ) ) );

        tasks.add( new StoreProcessorTask<RelationshipTypeRecord>( store.getRelationshipTypeStore(), progress,
                                                                   processEverything ) );
        tasks.add( new StoreProcessorTask<PropertyIndexRecord>( store.getPropertyIndexStore(), progress,
                                                                processEverything ) );

        tasks.add( new StoreProcessorTask<DynamicRecord>( store.getStringStore(), progress, processEverything ) );
        tasks.add( new StoreProcessorTask<DynamicRecord>( store.getArrayStore(), progress, processEverything ) );
        tasks.add( new StoreProcessorTask<DynamicRecord>( store.getTypeNameStore(), progress, processEverything ) );
        tasks.add( new StoreProcessorTask<DynamicRecord>( store.getPropertyKeyStore(), progress, processEverything ) );

        order.execute( processEverything, tasks, progress.build() );
    }

    public static void run( ProgressMonitorFactory progressFactory, String storeDir, Config config,
                            StringLogger logger ) throws ConsistencyCheckIncompleteException
    {
        StoreFactory factory = new StoreFactory(
                config,
                new DefaultIdGeneratorFactory(),
                windowPoolFactory( config, logger ),
                new DefaultFileSystemAbstraction(),
                new DefaultLastCommittedTxIdSetter(),
                logger,
                new DefaultTxHook() );
        NeoStore neoStore = factory.newNeoStore( new File( storeDir, NeoStore.DEFAULT_NAME ).getAbsolutePath() );
        try
        {
            StoreAccess store = new StoreAccess( neoStore );
            new FullCheck( config.get( consistency_check_property_owners ),
                           executionMode( config ),
                           progressFactory ).execute( store, logger );
        }
        finally
        {
            neoStore.close();
        }
    }

    private static TaskExecutionOrder executionMode( Config config )
    {
        if ( config.get( consistency_check_multiple_passes ) )
        {
            return MULTI_PASS;
        }
        else if ( config.get( consistency_check_single_threaded ) )
        {
            return SINGLE_THREADED;
        }
        else
        {
            return MULTI_THREADED;
        }
    }

    static DiffRecordAccess recordAccess( StoreAccess store )
    {
        return new CacheSmallStoresRecordAccess(
                new DirectRecordAccess( store ),
                readAllRecords( PropertyIndexRecord.class, store.getPropertyIndexStore() ),
                readAllRecords( RelationshipTypeRecord.class, store.getRelationshipTypeStore() ) );
    }

    private static <T extends AbstractBaseRecord> T[] readAllRecords( Class<T> type, RecordStore<T> store )
    {
        @SuppressWarnings("unchecked")
        T[] records = (T[]) Array.newInstance( type, (int) store.getHighId() );
        for ( int i = 0; i < records.length; i++ )
        {
            records[i] = store.forceGetRecord( i );
        }
        return records;
    }

    private static WindowPoolFactory windowPoolFactory( Config config, StringLogger logger )
    {
        if ( config.get( use_scan_resistant_window_pools ) )
        {
            return new ScanResistantWindowPoolFactory( config, logger );
        }
        else
        {
            return new DefaultWindowPoolFactory();
        }
    }
}
