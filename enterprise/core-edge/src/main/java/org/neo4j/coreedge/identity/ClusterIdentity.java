/*
 * Copyright (c) 2002-2016 "Neo Technology,"
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
package org.neo4j.coreedge.identity;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.neo4j.coreedge.core.state.CoreBootstrapper;
import org.neo4j.coreedge.core.state.snapshot.CoreSnapshot;
import org.neo4j.coreedge.core.state.storage.SimpleStorage;
import org.neo4j.coreedge.discovery.CoreTopology;
import org.neo4j.coreedge.discovery.CoreTopologyService;
import org.neo4j.function.ThrowingAction;
import org.neo4j.function.ThrowingConsumer;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

public class ClusterIdentity
{
    private final SimpleStorage<ClusterId> clusterIdStorage;
    private final CoreTopologyService topologyService;
    private final CoreBootstrapper coreBootstrapper;
    private final Log log;
    private final Clock clock;
    private final ThrowingAction<InterruptedException> retryWaiter;
    private final long timeoutMillis;

    private ClusterId clusterId;

    public ClusterIdentity( SimpleStorage<ClusterId> clusterIdStorage, CoreTopologyService topologyService,
                            LogProvider logProvider, Clock clock, ThrowingAction<InterruptedException> retryWaiter,
                            long timeoutMillis, CoreBootstrapper coreBootstrapper )
    {
        this.clusterIdStorage = clusterIdStorage;
        this.topologyService = topologyService;
        this.coreBootstrapper = coreBootstrapper;
        this.log = logProvider.getLog( getClass() );
        this.clock = clock;
        this.retryWaiter = retryWaiter;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * The cluster binding process tries to establish a common cluster ID. If there is no common cluster ID
     * then a single instance will eventually create one and publish it through the underlying topology service.
     *
     * @throws IOException If there is an issue with I/O.
     * @throws InterruptedException If the process gets interrupted.
     * @throws TimeoutException If the process times out.
     */
    public void bindToCluster( ThrowingConsumer<CoreSnapshot, Throwable> snapshotInstaller ) throws Throwable
    {
        if ( clusterIdStorage.exists() )
        {
            ClusterId localClusterId = clusterIdStorage.readState();
            publishClusterId( localClusterId );
            clusterId = localClusterId;
        }
        else
        {
            ClusterId commonClusterId;
            CoreTopology topology = topologyService.coreServers();
            if ( topology.canBeBootstrapped() )
            {
                commonClusterId = new ClusterId( UUID.randomUUID() );
                CoreSnapshot snapshot = coreBootstrapper.bootstrap( topology.members() );

                snapshotInstaller.accept( snapshot );
                publishClusterId( commonClusterId );
            }
            else
            {
                long endTime = clock.millis() + timeoutMillis;

                log.info( "Attempting to bind to : " + topology );
                while ( (commonClusterId = topology.clusterId()) == null )
                {
                    if ( clock.millis() < endTime )
                    {
                        retryWaiter.apply();
                        topology = topologyService.coreServers();
                    }
                    else
                    {
                        throw new TimeoutException( "Failed binding to cluster in time. Last topology was: " +
                                topology );
                    }
                }

                log.info( "Bound to cluster: " + commonClusterId );
            }

            clusterIdStorage.writeState( commonClusterId );

            clusterId = commonClusterId;
        }
    }

    public ClusterId clusterId()
    {
        return clusterId;
    }

    private void publishClusterId( ClusterId localClusterId ) throws BindingException
    {
        boolean success = topologyService.casClusterId( localClusterId );
        if ( !success )
        {
            throw new BindingException( "Failed to publish: " + localClusterId );
        }
        else
        {
            log.info( "Published: " + localClusterId );
        }
    }

}