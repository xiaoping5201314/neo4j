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
package org.neo4j.consistency.report;

import java.io.StringWriter;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.internal.matchers.TypeSafeMatcher;
import org.neo4j.consistency.RecordType;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreRecord;
import org.neo4j.kernel.impl.util.StringLogger;

import static org.junit.Assert.assertThat;

public class MessageConsistencyLoggerTest
{
    // given
    private final MessageConsistencyLogger logger;
    private final StringWriter writer;

    {
        writer = new StringWriter();
        logger = new MessageConsistencyLogger( StringLogger.wrap( writer ) );
    }

    @Test
    public void shouldFormatErrorForRecord() throws Exception
    {
        // when
        logger.error( RecordType.NEO_STORE, new NeoStoreRecord(), "sample message", 1, 2 );

        // then
        assertTextEquals( "ERROR: sample message",
                          "NeoStoreRecord[used=true,nextProp=-1]",
                          "Inconsistent with: 1 2" );
    }

    @Test
    public void shouldFormatWarningForRecord() throws Exception
    {
        // when
        logger.warning( RecordType.NEO_STORE, new NeoStoreRecord(), "sample message", 1, 2 );

        // then
        assertTextEquals( "WARNING: sample message",
                          "NeoStoreRecord[used=true,nextProp=-1]",
                          "Inconsistent with: 1 2" );
    }

    @Test
    public void shouldFormatErrorForChangedRecord() throws Exception
    {
        // when
        logger.error( RecordType.NEO_STORE, new NeoStoreRecord(), new NeoStoreRecord(), "sample message", 1, 2 );

        // then
        assertTextEquals( "ERROR: sample message",
                          "- NeoStoreRecord[used=true,nextProp=-1]",
                          "+ NeoStoreRecord[used=true,nextProp=-1]",
                          "Inconsistent with: 1 2" );
    }

    @Test
    public void shouldFormatWarningForChangedRecord() throws Exception
    {
        // when
        logger.warning( RecordType.NEO_STORE, new NeoStoreRecord(), new NeoStoreRecord(), "sample message", 1, 2 );

        // then
        assertTextEquals( "WARNING: sample message",
                          "- NeoStoreRecord[used=true,nextProp=-1]",
                          "+ NeoStoreRecord[used=true,nextProp=-1]",
                          "Inconsistent with: 1 2" );
    }

    private void assertTextEquals( String firstLine, String... lines )
    {
        StringBuilder expected = new StringBuilder( firstLine );
        for ( String line : lines )
        {
            expected.append( "\n\t" ).append( line );
        }
        assertThat( writer.toString(), endsWith( expected.append( '\n' ).toString() ) );
    }

    private static Matcher<String> endsWith( final String suffix )
    {
        return new TypeSafeMatcher<String>()
        {
            @Override
            public boolean matchesSafely( String item )
            {
                return item.endsWith( suffix );
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( "String ending with " ).appendValue( suffix );
            }
        };
    }
}
