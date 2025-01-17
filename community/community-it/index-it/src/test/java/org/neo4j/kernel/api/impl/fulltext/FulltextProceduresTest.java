/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.fulltext;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.queryparser.flexible.standard.QueryParserUtil;
import org.eclipse.collections.api.iterator.MutableLongIterator;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.IndexSetting;
import org.neo4j.graphdb.schema.IndexSettingImpl;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.kernel.api.exceptions.schema.RepeatedLabelInSchemaException;
import org.neo4j.kernel.api.exceptions.schema.RepeatedPropertyInSchemaException;
import org.neo4j.kernel.api.exceptions.schema.RepeatedRelationshipTypeInSchemaException;
import org.neo4j.kernel.impl.index.schema.FulltextIndexProviderFactory;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Level;
import org.neo4j.procedure.builtin.FulltextProcedures;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.test.ThreadTestUtils;
import org.neo4j.test.rule.CleanupRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.VerboseTimeout;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;
import org.neo4j.util.concurrent.BinaryLatch;
import org.neo4j.values.storable.RandomValues;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueGroup;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.apache.commons.lang3.exception.ExceptionUtils.getRootCause;
import static org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.newSetWith;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.internal.helpers.collection.Iterables.single;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.AWAIT_REFRESH;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.DB_AWAIT_INDEX;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.DB_INDEXES;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.DROP;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.LIST_AVAILABLE_ANALYZERS;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.NODE_CREATE;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.QUERY_NODES;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.QUERY_RELS;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.RELATIONSHIP_CREATE;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProceduresUtil.asCypherStringsList;

public class FulltextProceduresTest
{
    private static final String SCORE = "score";
    private static final String NODE = "node";
    private static final String RELATIONSHIP = "relationship";
    private static final String DESCARTES_MEDITATIONES = "/meditationes--rene-descartes--public-domain.txt";
    private static final Label LABEL = Label.label( "Label" );
    private static final RelationshipType REL = RelationshipType.withName( "REL" );

    private final Timeout timeout = VerboseTimeout.builder().withTimeout( 1, TimeUnit.HOURS ).build();
    private final DefaultFileSystemRule fs = new DefaultFileSystemRule();
    private final TestDirectory testDirectory = TestDirectory.testDirectory();
    private final ExpectedException expectedException = ExpectedException.none();
    private final CleanupRule cleanup = new CleanupRule();

    @Rule
    public final RuleChain rules = RuleChain.outerRule( timeout ).around( fs ).around( testDirectory ).around( expectedException ).around( cleanup );

    private GraphDatabaseAPI db;
    private DatabaseManagementServiceBuilder builder;
    private static final String PROP = "prop";
    private static final String EVENTUALLY_CONSISTENT = ", {eventually_consistent: 'true'}";
    private static final String EVENTUALLY_CONSISTENT_PREFIXED = ", {`fulltext.eventually_consistent`: 'true'}";
    private DatabaseManagementService managementService;

    @Before
    public void before()
    {
        builder = new DatabaseManagementServiceBuilder( testDirectory.homeDir() );
        builder.setConfig( GraphDatabaseSettings.store_internal_log_level, Level.DEBUG );
    }

    @After
    public void tearDown()
    {
        if ( db != null )
        {
            managementService.shutdown();
        }
    }

    @Test
    public void createNodeFulltextIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction
                    .execute( format( NODE_CREATE, "test-index", asCypherStringsList( "Label1", "Label2" ), asCypherStringsList( "prop1", "prop2" ) ) )
                    .close();
            transaction.commit();
        }
        Result result;
        Map<String,Object> row;
        try ( Transaction tx = db.beginTx() )
        {
            result = tx.execute( DB_INDEXES );
            assertTrue( result.hasNext() );
            row = result.next();
            assertEquals( asList( "Label1", "Label2" ), row.get( "labelsOrTypes" ) );
            assertEquals( asList( "prop1", "prop2" ), row.get( "properties" ) );
            assertEquals( "test-index", row.get( "name" ) );
            assertEquals( "FULLTEXT", row.get( "type" ) );
            assertFalse( result.hasNext() );
            result.close();
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            result = tx.execute( DB_INDEXES );
            assertTrue( result.hasNext() );
            assertEquals( "ONLINE", result.next().get( "state" ) );
            assertFalse( result.hasNext() );
            result.close();
            assertNotNull( tx.schema().getIndexByName( "test-index" ) );
            tx.commit();
        }
        managementService.shutdown();
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            result = tx.execute( DB_INDEXES );
            assertTrue( result.hasNext() );
            row = result.next();
            assertEquals( asList( "Label1", "Label2" ), row.get( "labelsOrTypes" ) );
            assertEquals( asList( "prop1", "prop2" ), row.get( "properties" ) );
            assertEquals( "test-index", row.get( "name" ) );
            assertEquals( "FULLTEXT", row.get( "type" ) );
            assertEquals( "ONLINE", row.get( "state" ) );
            assertFalse( result.hasNext() );
            assertFalse( result.hasNext() );
            assertNotNull( tx.schema().getIndexByName( "test-index" ) );
            tx.commit();
        }
    }

    @Test
    public void createRelationshipFulltextIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction
                    .execute( format( RELATIONSHIP_CREATE, "test-index", asCypherStringsList( "Reltype1", "Reltype2" ),
                            asCypherStringsList( "prop1", "prop2" ) ) )
                    .close();
            transaction.commit();
        }
        Result result;
        Map<String,Object> row;
        try ( Transaction tx = db.beginTx() )
        {
            result = tx.execute( DB_INDEXES );
            assertTrue( result.hasNext() );
            row = result.next();
            assertEquals( asList( "Reltype1", "Reltype2" ), row.get( "labelsOrTypes" ) );
            assertEquals( asList( "prop1", "prop2" ), row.get( "properties" ) );
            assertEquals( "test-index", row.get( "name" ) );
            assertEquals( "FULLTEXT", row.get( "type" ) );
            assertFalse( result.hasNext() );
            result.close();
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            result = tx.execute( DB_INDEXES );
            assertTrue( result.hasNext() );
            assertEquals( "ONLINE", result.next().get( "state" ) );
            assertFalse( result.hasNext() );
            result.close();
            assertNotNull( tx.schema().getIndexByName( "test-index" ) );
            tx.commit();
        }
        managementService.shutdown();
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            result = tx.execute( DB_INDEXES );
            assertTrue( result.hasNext() );
            row = result.next();
            assertEquals( asList( "Reltype1", "Reltype2" ), row.get( "labelsOrTypes" ) );
            assertEquals( asList( "prop1", "prop2" ), row.get( "properties" ) );
            assertEquals( "test-index", row.get( "name" ) );
            assertEquals( "FULLTEXT", row.get( "type" ) );
            assertEquals( "ONLINE", row.get( "state" ) );
            assertFalse( result.hasNext() );
            assertFalse( result.hasNext() );
            assertNotNull( tx.schema().getIndexByName( "test-index" ) );
            tx.commit();
        }
    }

    @Test
    public void dropIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1", "Label2" ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            transaction
                    .execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( "Reltype1", "Reltype2" ), asCypherStringsList( "prop1", "prop2" ) ) )
                    .close();
            Map<String,String> indexes = new HashMap<>();
            transaction.execute( "call db.indexes()" ).forEachRemaining( m -> indexes.put( (String) m.get( "name" ), (String) m.get( "description" ) ) );

            transaction.execute( format( DROP, "node" ) );
            indexes.remove( "node" );
            Map<String,String> newIndexes = new HashMap<>();
            transaction.execute( "call db.indexes()" ).forEachRemaining(
                    m -> newIndexes.put( (String) m.get( "name" ), (String) m.get( "description" ) ) );
            assertEquals( indexes, newIndexes );

            transaction.execute( format( DROP, "rel" ) );
            indexes.remove( "rel" );
            newIndexes.clear();
            transaction.execute( "call db.indexes()" ).forEachRemaining(
                    m -> newIndexes.put( (String) m.get( "name" ), (String) m.get( "description" ) ) );
            assertEquals( indexes, newIndexes );
            transaction.commit();
        }
    }

    @Test
    public void mustNotBeAbleToCreateTwoIndexesWithSameName()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1", "Label2" ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            transaction.commit();
        }
        expectedException.expectMessage( "already exists" );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1", "Label2" ), asCypherStringsList( "prop3", "prop4" ) ) ).close();
            transaction.commit();
        }
    }

    @Test
    public void mustNotBeAbleToCreateNormalIndexWithSameNameAndSchemaAsExistingFulltextIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1" ), asCypherStringsList( "prop1" ) ) ).close();
            transaction.commit();
        }
        expectedException.expectMessage( "already exists" );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( "CREATE INDEX `node` FOR (n:Label1) ON (n.prop1)" ).close();
            transaction.commit();
        }
    }

    @Test
    public void mustNotBeAbleToCreateNormalIndexWithSameNameDifferentSchemaAsExistingFulltextIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1" ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            transaction.commit();
        }
        expectedException.expectMessage( "There already exists an index called 'node'." );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( "CREATE INDEX `node` FOR (n:Label1) ON (n.prop1)" ).close();
            transaction.commit();
        }
    }

    @Test
    public void mustNotBeAbleToCreateFulltextIndexWithSameNameAndSchemaAsExistingNormalIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( "CREATE INDEX `node` FOR (n:Label1) ON (n.prop1)" ).close();
            transaction.commit();
        }
        expectedException.expectMessage( "already exists" );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1" ), asCypherStringsList( "prop1" ) ) ).close();
            transaction.commit();
        }
    }

    @Test
    public void mustNotBeAbleToCreateFulltextIndexWithSameNameDifferentSchemaAsExistingNormalIndex()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( "CREATE INDEX `node` FOR (n:Label1) ON (n.prop1)" ).close();
            transaction.commit();
        }
        expectedException.expectMessage( "already exists" );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1" ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            transaction.commit();
        }
    }

    @Test
    public void nodeIndexesMustHaveLabels()
    {
        db = createDatabase();
        expectedException.expect( QueryExecutionException.class );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "nodeIndex", asCypherStringsList(), asCypherStringsList( PROP ) ) ).close();
        }
    }

    @Test
    public void relationshipIndexesMustHaveRelationshipTypes()
    {
        db = createDatabase();
        expectedException.expect( QueryExecutionException.class );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( RELATIONSHIP_CREATE, "relIndex", asCypherStringsList(), asCypherStringsList( PROP ) ) );
        }
    }

    @Test
    public void nodeIndexesMustHaveProperties()
    {
        db = createDatabase();
        expectedException.expect( QueryExecutionException.class );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "nodeIndex", asCypherStringsList( "Label" ), asCypherStringsList() ) ).close();
        }
    }

    @Test
    public void relationshipIndexesMustHaveProperties()
    {
        db = createDatabase();
        expectedException.expect( QueryExecutionException.class );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( RELATIONSHIP_CREATE, "relIndex", asCypherStringsList( "RELTYPE" ), asCypherStringsList() ) );
        }
    }

    @Test
    public void creatingIndexesWhichImpliesTokenCreateMustNotBlockForever()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            // The property keys and labels we ask for do not exist, so those tokens will have to be allocated.
            // This test verifies that the locking required for the index modifications do not conflict with the
            // locking required for the token allocation.
            tx.execute( format( NODE_CREATE, "nodesA", asCypherStringsList( "SOME_LABEL" ), asCypherStringsList( "this" ) ) );
            tx.execute( format( RELATIONSHIP_CREATE, "relsA", asCypherStringsList( "SOME_REL_TYPE" ), asCypherStringsList( "foo" ) ) );
            tx.execute( format( NODE_CREATE, "nodesB", asCypherStringsList( "SOME_OTHER_LABEL" ), asCypherStringsList( "that" ) ) );
            tx.execute( format( RELATIONSHIP_CREATE, "relsB", asCypherStringsList( "SOME_OTHER_REL_TYPE" ), asCypherStringsList( "bar" ) ) );
        }
    }

    @Test
    public void creatingIndexWithSpecificAnalyzerMustUseThatAnalyzerForPopulationUpdatingAndQuerying()
    {
        db = createDatabase();
        LongHashSet noResults = new LongHashSet();
        LongHashSet swedishNodes = new LongHashSet();
        LongHashSet englishNodes = new LongHashSet();
        LongHashSet swedishRels = new LongHashSet();
        LongHashSet englishRels = new LongHashSet();

        String labelledSwedishNodes = "labelledSwedishNodes";
        String typedSwedishRelationships = "typedSwedishRelationships";

        try ( Transaction tx = db.beginTx() )
        {
            // Nodes and relationships picked up by index population.
            Node nodeA = tx.createNode( LABEL );
            nodeA.setProperty( PROP, "En apa och en tomte bodde i ett hus." );
            swedishNodes.add( nodeA.getId() );
            Node nodeB = tx.createNode( LABEL );
            nodeB.setProperty( PROP, "Hello and hello again, in the end." );
            englishNodes.add( nodeB.getId() );
            Relationship relA = nodeA.createRelationshipTo( nodeB, REL );
            relA.setProperty( PROP, "En apa och en tomte bodde i ett hus." );
            swedishRels.add( relA.getId() );
            Relationship relB = nodeB.createRelationshipTo( nodeA, REL );
            relB.setProperty( PROP, "Hello and hello again, in the end." );
            englishRels.add( relB.getId() );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            String lbl = asCypherStringsList( LABEL.name() );
            String rel = asCypherStringsList( REL.name() );
            String props = asCypherStringsList( PROP );
            String swedish = props + ", {analyzer: '" + FulltextAnalyzerTest.SWEDISH + "'}";
            tx.execute( format( NODE_CREATE, labelledSwedishNodes, lbl, swedish ) ).close();
            tx.execute( format( RELATIONSHIP_CREATE, typedSwedishRelationships, rel, swedish ) ).close();
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            // Nodes and relationships picked up by index updates.
            Node nodeC = tx.createNode( LABEL );
            nodeC.setProperty( PROP, "En apa och en tomte bodde i ett hus." );
            swedishNodes.add( nodeC.getId() );
            Node nodeD = tx.createNode( LABEL );
            nodeD.setProperty( PROP, "Hello and hello again, in the end." );
            englishNodes.add( nodeD.getId() );
            Relationship relC = nodeC.createRelationshipTo( nodeD, REL );
            relC.setProperty( PROP, "En apa och en tomte bodde i ett hus." );
            swedishRels.add( relC.getId() );
            Relationship relD = nodeD.createRelationshipTo( nodeC, REL );
            relD.setProperty( PROP, "Hello and hello again, in the end." );
            englishRels.add( relD.getId() );
            tx.commit();
        }
        assertQueryFindsIds( db, true, labelledSwedishNodes, "and", englishNodes ); // english word
        // swedish stop word (ignored by swedish analyzer, and not among the english nodes)
        assertQueryFindsIds( db, true, labelledSwedishNodes, "ett", noResults );
        assertQueryFindsIds( db, true, labelledSwedishNodes, "apa", swedishNodes ); // swedish word

        assertQueryFindsIds( db, false, typedSwedishRelationships, "and", englishRels );
        assertQueryFindsIds( db, false, typedSwedishRelationships, "ett", noResults );
        assertQueryFindsIds( db, false, typedSwedishRelationships, "apa", swedishRels );
    }

    @Test
    public void queryShouldFindDataAddedInLaterTransactions()
    {
        db = createDatabase();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( format( NODE_CREATE, "node", asCypherStringsList( "Label1", "Label2" ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            transaction
                    .execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( "Reltype1", "Reltype2" ), asCypherStringsList( "prop1", "prop2" ) ) )
                    .close();
            transaction.commit();
        }
        awaitIndexesOnline();
        long horseId;
        long horseRelId;
        try ( Transaction tx = db.beginTx() )
        {
            Node zebra = tx.createNode();
            zebra.setProperty( "prop1", "zebra" );
            Node horse = tx.createNode( Label.label( "Label1" ) );
            horse.setProperty( "prop2", "horse" );
            horse.setProperty( "prop3", "zebra" );
            Relationship horseRel = zebra.createRelationshipTo( horse, RelationshipType.withName( "Reltype1" ) );
            horseRel.setProperty( "prop1", "horse" );
            Relationship loop = horse.createRelationshipTo( horse, RelationshipType.withName( "loop" ) );
            loop.setProperty( "prop2", "zebra" );

            horseId = horse.getId();
            horseRelId = horseRel.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "node", "horse", newSetWith( horseId ) );
        assertQueryFindsIds( db, true, "node", "horse zebra", newSetWith( horseId ) );

        assertQueryFindsIds( db, false, "rel", "horse", newSetWith( horseRelId ) );
        assertQueryFindsIds( db, false, "rel", "horse zebra", newSetWith( horseRelId ) );
    }

    @Test
    public void queryShouldFindDataAddedInIndexPopulation()
    {
        // when
        Node node1;
        Node node2;
        Relationship relationship;
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            node1 = tx.createNode( LABEL );
            node1.setProperty( PROP, "This is a integration test." );
            node2 = tx.createNode( LABEL );
            node2.setProperty( "otherprop", "This is a related integration test" );
            relationship = node1.createRelationshipTo( node2, REL );
            relationship.setProperty( PROP, "They relate" );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "node", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP, "otherprop" ) ) );
            tx.execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) ) );
            tx.commit();
        }
        awaitIndexesOnline();

        // then
        assertQueryFindsIds( db, true, "node", "integration", node1.getId(), node2.getId() );
        assertQueryFindsIds( db, true, "node", "test", node1.getId(), node2.getId() );
        assertQueryFindsIds( db, true, "node", "related", newSetWith( node2.getId() ) );
        assertQueryFindsIds( db, false, "rel", "relate", newSetWith( relationship.getId() ) );
    }

    @Test
    public void updatesToEventuallyConsistentIndexMustEventuallyBecomeVisible()
    {
        String value = "bla bla";
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "node", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
            tx.execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
            tx.commit();
        }

        int entityCount = 200;
        LongHashSet nodeIds = new LongHashSet();
        LongHashSet relIds = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < entityCount; i++ )
            {
                Node node = tx.createNode( LABEL );
                node.setProperty( PROP, value );
                Relationship rel = node.createRelationshipTo( node, REL );
                rel.setProperty( PROP, value );
                nodeIds.add( node.getId() );
                relIds.add( rel.getId() );
            }
            tx.commit();
        }

        // Assert that we can observe our updates within 20 seconds from now. We have, after all, already committed the transaction.
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis( 20 );
        boolean success = false;
        do
        {
            try
            {
                assertQueryFindsIds( db, true, "node", "bla", nodeIds );
                assertQueryFindsIds( db, false, "rel", "bla", relIds );
                success = true;
            }
            catch ( Throwable throwable )
            {
                if ( deadline <= System.currentTimeMillis() )
                {
                    // We're past the deadline. This test is not successful.
                    throw throwable;
                }
            }
        }
        while ( !success );
    }

    @Test
    public void updatesToEventuallyConsistentIndexMustBecomeVisibleAfterAwaitRefresh()
    {
        String value = "bla bla";
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "node", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
            tx.execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
            tx.commit();
        }
        awaitIndexesOnline();

        int entityCount = 200;
        LongHashSet nodeIds = new LongHashSet();
        LongHashSet relIds = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < entityCount; i++ )
            {
                Node node = tx.createNode( LABEL );
                node.setProperty( PROP, value );
                Relationship rel = node.createRelationshipTo( node, REL );
                rel.setProperty( PROP, value );
                nodeIds.add( node.getId() );
                relIds.add( rel.getId() );
            }
            tx.commit();
        }

        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( AWAIT_REFRESH ).close();
            transaction.commit();
        }
        assertQueryFindsIds( db, true, "node", "bla", nodeIds );
        assertQueryFindsIds( db, false, "rel", "bla", relIds );
    }

    @Test
    public void eventuallyConsistentIndexMustPopulateWithExistingDataWhenCreated()
    {
        String value = "bla bla";
        db = createDatabase();

        int entityCount = 200;
        LongHashSet nodeIds = new LongHashSet();
        LongHashSet relIds = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < entityCount; i++ )
            {
                Node node = tx.createNode( LABEL );
                node.setProperty( PROP, value );
                Relationship rel = node.createRelationshipTo( node, REL );
                rel.setProperty( PROP, value );
                nodeIds.add( node.getId() );
                relIds.add( rel.getId() );
            }
            tx.commit();
        }

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "node", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
            tx.execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
            tx.commit();
        }

        awaitIndexesOnline();
        assertQueryFindsIds( db, true, "node", "bla", nodeIds );
        assertQueryFindsIds( db, false, "rel", "bla", relIds );
    }

    @Test
    public void concurrentPopulationAndUpdatesToAnEventuallyConsistentIndexMustEventuallyResultInCorrectIndexState() throws Exception
    {
        String oldValue = "red";
        String newValue = "green";
        db = createDatabase();

        int entityCount = 200;
        LongHashSet nodeIds = new LongHashSet();
        LongHashSet relIds = new LongHashSet();

        // First we create the nodes and relationships with the property value "red".
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < entityCount; i++ )
            {
                Node node = tx.createNode( LABEL );
                node.setProperty( PROP, oldValue );
                Relationship rel = node.createRelationshipTo( node, REL );
                rel.setProperty( PROP, oldValue );
                nodeIds.add( node.getId() );
                relIds.add( rel.getId() );
            }
            tx.commit();
        }

        // Then, in two concurrent transactions, we create our indexes AND change all the property values to "green".
        CountDownLatch readyLatch = new CountDownLatch( 2 );
        BinaryLatch startLatch = new BinaryLatch();
        Runnable createIndexes = () ->
        {
            readyLatch.countDown();
            startLatch.await();
            try ( Transaction tx = db.beginTx() )
            {
                tx.execute( format( NODE_CREATE, "node", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
                tx.execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) );
                tx.commit();
            }
        };
        Runnable makeAllEntitiesGreen = () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                // Prepare our transaction state first.
                nodeIds.forEach( nodeId -> tx.getNodeById( nodeId ).setProperty( PROP, newValue ) );
                relIds.forEach( relId -> tx.getRelationshipById( relId ).setProperty( PROP, newValue ) );
                tx.commit();
                // Okay, NOW we're ready to race!
                readyLatch.countDown();
                startLatch.await();
            }
        };
        ExecutorService executor = cleanup.add( Executors.newFixedThreadPool( 2 ) );
        Future<?> future1 = executor.submit( createIndexes );
        Future<?> future2 = executor.submit( makeAllEntitiesGreen );
        readyLatch.await();
        startLatch.release();

        // Finally, when everything has settled down, we should see that all of the nodes and relationships are indexed with the value "green".
        future1.get();
        future2.get();
        awaitIndexesOnline();
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( AWAIT_REFRESH ).close();
        }
        assertQueryFindsIds( db, true, "node", newValue, nodeIds );
        assertQueryFindsIds( db, false, "rel", newValue, relIds );
    }

    @Test
    public void fulltextIndexesMustBeEventuallyConsistentByDefaultWhenThisIsConfigured() throws InterruptedException
    {
        builder.setConfig( FulltextSettings.eventually_consistent, true );
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "node", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP, "otherprop" ) ) );
            tx.execute( format( RELATIONSHIP_CREATE, "rel", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) ) );
            tx.commit();
        }
        awaitIndexesOnline();

        // Prevent index updates from being applied to eventually consistent indexes.
        BinaryLatch indexUpdateBlocker = new BinaryLatch();
        db.getDependencyResolver().resolveDependency( JobScheduler.class ).schedule( Group.INDEX_UPDATING, indexUpdateBlocker::await );

        LongHashSet nodeIds = new LongHashSet();
        long relId;
        try
        {
            try ( Transaction tx = db.beginTx() )
            {
                Node node1 = tx.createNode( LABEL );
                node1.setProperty( PROP, "bla bla" );
                Node node2 = tx.createNode( LABEL );
                node2.setProperty( "otherprop", "bla bla" );
                Relationship relationship = node1.createRelationshipTo( node2, REL );
                relationship.setProperty( PROP, "bla bla" );
                nodeIds.add( node1.getId() );
                nodeIds.add( node2.getId() );
                relId = relationship.getId();
                tx.commit();
            }

            // Index updates are still blocked for eventually consistent indexes, so we should not find anything at this point.
            assertQueryFindsIds( db, true, "node", "bla", new LongHashSet() );
            assertQueryFindsIds( db, false, "rel", "bla", new LongHashSet() );
        }
        finally
        {
            // Uncork the eventually consistent fulltext index updates.
            Thread.sleep( 10 );
            indexUpdateBlocker.release();
        }
        // And wait for them to apply.
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( AWAIT_REFRESH ).close();
            transaction.commit();
        }

        // Now we should see our data.
        assertQueryFindsIds( db, true, "node", "bla", nodeIds );
        assertQueryFindsIds( db, false, "rel", "bla", newSetWith( relId ) );
    }

    @Test
    public void mustBeAbleToListAvailableAnalyzers()
    {
        db = createDatabase();

        // Verify that a couple of expected analyzers are available.
        try ( Transaction tx = db.beginTx() )
        {
            Set<String> analyzers = new HashSet<>();
            try ( ResourceIterator<String> iterator = tx.execute( LIST_AVAILABLE_ANALYZERS ).columnAs( "analyzer" ) )
            {
                while ( iterator.hasNext() )
                {
                    analyzers.add( iterator.next() );
                }
            }
            assertThat( analyzers, hasItem( "english" ) );
            assertThat( analyzers, hasItem( "swedish" ) );
            assertThat( analyzers, hasItem( "standard" ) );
            assertThat( analyzers, hasItem( "galician" ) );
            assertThat( analyzers, hasItem( "irish" ) );
            assertThat( analyzers, hasItem( "latvian" ) );
            assertThat( analyzers, hasItem( "sorani" ) );
            tx.commit();
        }

        // Verify that all analyzers have a description.
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( LIST_AVAILABLE_ANALYZERS ) )
            {
                while ( result.hasNext() )
                {
                    Map<String,Object> row = result.next();
                    Object description = row.get( "description" );
                    if ( !row.containsKey( "description" ) || !(description instanceof String) || ((String) description).trim().isEmpty() )
                    {
                        fail( "Found no description for analyzer: " + row );
                    }
                }
            }
            tx.commit();
        }
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void analyzersMustKnowTheirStopWords()
    {
        db = createDatabase();

        // Verify that analyzers have stop-words.
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( LIST_AVAILABLE_ANALYZERS ) )
            {
                while ( result.hasNext() )
                {
                    Map<String,Object> row = result.next();
                    Object stopwords = row.get( "stopwords" );
                    if ( !row.containsKey( "stopwords" ) || !(stopwords instanceof List) )
                    {
                        fail( "Found no stop-words list for analyzer: " + row );
                    }

                    List<String> words = (List<String>) stopwords;
                    String analyzerName = (String) row.get( "analyzer" );
                    if ( analyzerName.equals( "english" ) || analyzerName.equals( "standard" ) )
                    {
                        assertThat( words, hasItem( "and" ) );
                    }
                    else if ( analyzerName.equals( "standard-no-stop-words" ) )
                    {
                        assertTrue( words.isEmpty() );
                    }
                }
            }
            tx.commit();
        }

        // Verify that the stop-words data-sets are clean; that they contain no comments, white-space or empty strings.
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( LIST_AVAILABLE_ANALYZERS ) )
            {
                while ( result.hasNext() )
                {
                    Map<String,Object> row = result.next();
                    List<String> stopwords = (List<String>) row.get( "stopwords" );
                    for ( String stopword : stopwords )
                    {
                        if ( stopword.isBlank() || stopword.contains( "#" ) || stopword.contains( " " ) )
                        {
                            fail( "The list of stop-words for the " + row.get( "analyzer" ) + " analyzer contains dirty data. " +
                                    "Specifically, '" + stopword + "' does not look like a valid stop-word. The full list:" +
                                    System.lineSeparator() + stopwords );
                        }
                    }
                }
            }
            tx.commit();
        }
    }

    @Test
    public void queryNodesMustThrowWhenQueryingRelationshipIndex()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }

        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            expectedException.expect( Exception.class );
            tx.execute( format( QUERY_NODES, "rels", "bla bla" ) ).next();
            tx.commit();
        }
    }

    @Test
    public void queryRelationshipsMustThrowWhenQueryingNodeIndex()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }

        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            expectedException.expect( Exception.class );
            tx.execute( format( QUERY_RELS, "nodes", "bla bla" ) ).next();
            tx.commit();
        }
    }

    @Test
    public void fulltextIndexMustIgnoreNonStringPropertiesForUpdate()
    {
        db = createDatabase();

        Label label = LABEL;
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( label.name() ), asCypherStringsList( PROP ) ) ).close();
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }

        awaitIndexesOnline();

        List<Value> values = generateRandomNonStringValues();

        try ( Transaction tx = db.beginTx() )
        {
            for ( Value value : values )
            {
                Node node = tx.createNode( label );
                Object propertyValue = value.asObject();
                node.setProperty( PROP, propertyValue );
                node.createRelationshipTo( node, REL ).setProperty( PROP, propertyValue );
            }
            tx.commit();
        }

        for ( Value value : values )
        {
            try ( Transaction transaction = db.beginTx() )
            {
                String fulltextQuery = quoteValueForQuery( value );
                String cypherQuery = format( QUERY_NODES, "nodes", fulltextQuery );
                Result nodes;
                try
                {
                    nodes = transaction.execute( cypherQuery );
                }
                catch ( QueryExecutionException e )
                {
                    throw new AssertionError( "Failed to execute query: " + cypherQuery + " based on value " + value.prettyPrint(), e );
                }
                if ( nodes.hasNext() )
                {
                    fail( "did not expect to find any nodes, but found at least: " + nodes.next() );
                }
                nodes.close();
                Result relationships = transaction.execute( format( QUERY_RELS, "rels", fulltextQuery ) );
                if ( relationships.hasNext() )
                {
                    fail( "did not expect to find any relationships, but found at least: " + relationships.next() );
                }
                relationships.close();
                transaction.commit();
            }
        }
    }

    @Test
    public void fulltextIndexMustIgnoreNonStringPropertiesForPopulation()
    {
        db = createDatabase();

        List<Value> values = generateRandomNonStringValues();

        try ( Transaction tx = db.beginTx() )
        {
            for ( Value value : values )
            {
                Node node = tx.createNode( LABEL );
                Object propertyValue = value.asObject();
                node.setProperty( PROP, propertyValue );
                node.createRelationshipTo( node, REL ).setProperty( PROP, propertyValue );
            }
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }

        awaitIndexesOnline();

        for ( Value value : values )
        {
            try ( Transaction transaction = db.beginTx() )
            {
                String fulltextQuery = quoteValueForQuery( value );
                String cypherQuery = format( QUERY_NODES, "nodes", fulltextQuery );
                Result nodes;
                try
                {
                    nodes = transaction.execute( cypherQuery );
                }
                catch ( QueryExecutionException e )
                {
                    throw new AssertionError( "Failed to execute query: " + cypherQuery + " based on value " + value.prettyPrint(), e );
                }
                if ( nodes.hasNext() )
                {
                    fail( "did not expect to find any nodes, but found at least: " + nodes.next() );
                }
                nodes.close();
                Result relationships = transaction.execute( format( QUERY_RELS, "rels", fulltextQuery ) );
                if ( relationships.hasNext() )
                {
                    fail( "did not expect to find any relationships, but found at least: " + relationships.next() );
                }
                relationships.close();
                transaction.commit();
            }
        }
    }

    @Test
    public void entitiesMustBeRemovedFromFulltextIndexWhenPropertyValuesChangeAwayFromText()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            nodeId = node.getId();
            node.setProperty( PROP, "bla bla" );
            tx.commit();
        }

        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( PROP, 42 );
            tx.commit();
        }

        try ( Transaction tx = db.beginTx() )
        {
            Result result = tx.execute( format( QUERY_NODES, "nodes", "bla" ) );
            assertFalse( result.hasNext() );
            result.close();
            tx.commit();
        }
    }

    @Test
    public void entitiesMustBeAddedToFulltextIndexWhenPropertyValuesChangeToText()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, 42 );
            nodeId = node.getId();
            tx.commit();
        }

        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( PROP, "bla bla" );
            tx.commit();
        }

        assertQueryFindsIds( db, true, "nodes", "bla", nodeId );
    }

    @Test
    public void propertiesMustBeRemovedFromFulltextIndexWhenTheirValueTypeChangesAwayFromText()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            tx.commit();
        }
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            nodeId = node.getId();
            node.setProperty( "prop1", "foo" );
            node.setProperty( "prop2", "bar" );
            tx.commit();
        }

        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( "prop2", 42 );
            tx.commit();
        }

        assertQueryFindsIds( db, true, "nodes", "foo", nodeId );
        try ( Transaction tx = db.beginTx() )
        {
            Result result = tx.execute( format( QUERY_NODES, "nodes", "bar" ) );
            assertFalse( result.hasNext() );
            result.close();
            tx.commit();
        }
    }

    @Test
    public void propertiesMustBeAddedToFulltextIndexWhenTheirValueTypeChangesToText()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( "prop1", "prop2" ) ) ).close();
            tx.commit();
        }
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            nodeId = node.getId();
            node.setProperty( "prop1", "foo" );
            node.setProperty( "prop2", 42 );
            tx.commit();
        }

        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( "prop2", "bar" );
            tx.commit();
        }

        assertQueryFindsIds( db, true, "nodes", "foo", nodeId );
        assertQueryFindsIds( db, true, "nodes", "bar", nodeId );
    }

    @Test
    public void mustBeAbleToIndexHugeTextPropertiesInIndexUpdates() throws Exception
    {
        String meditationes;
        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( getClass().getResourceAsStream( DESCARTES_MEDITATIONES ), StandardCharsets.UTF_8 ) ) )
        {
            meditationes = reader.lines().collect( Collectors.joining( "\n" ) );
        }

        db = createDatabase();

        Label label = Label.label( "Book" );
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "books", asCypherStringsList( label.name() ), asCypherStringsList( "title", "author", "contents" ) ) ).close();
            tx.commit();
        }
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( label );
            nodeId = node.getId();
            node.setProperty( "title", "Meditationes de prima philosophia" );
            node.setProperty( "author", "René Descartes" );
            node.setProperty( "contents", meditationes );
            tx.commit();
        }

        awaitIndexesOnline();

        assertQueryFindsIds( db, true, "books", "impellit scriptum offerendum", nodeId );
    }

    @Test
    public void mustBeAbleToIndexHugeTextPropertiesInIndexPopulation() throws Exception
    {
        String meditationes;
        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( getClass().getResourceAsStream( DESCARTES_MEDITATIONES ), StandardCharsets.UTF_8 ) ) )
        {
            meditationes = reader.lines().collect( Collectors.joining( "\n" ) );
        }

        db = createDatabase();

        Label label = Label.label( "Book" );
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( label );
            nodeId = node.getId();
            node.setProperty( "title", "Meditationes de prima philosophia" );
            node.setProperty( "author", "René Descartes" );
            node.setProperty( "contents", meditationes );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "books", asCypherStringsList( label.name() ), asCypherStringsList( "title", "author", "contents" ) ) ).close();
            tx.commit();
        }

        awaitIndexesOnline();

        assertQueryFindsIds( db, true, "books", "impellit scriptum offerendum", nodeId );
    }

    @Test
    public void mustBeAbleToQuerySpecificPropertiesViaLuceneSyntax()
    {
        db = createDatabase();
        Label book = Label.label( "Book" );
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "books", asCypherStringsList( book.name() ), asCypherStringsList( "title", "author" ) ) ).close();
            tx.commit();
        }

        long book2id;
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
            Node book1 = tx.createNode( book );
            book1.setProperty( "author", "René Descartes" );
            book1.setProperty( "title", "Meditationes de prima philosophia" );
            Node book2 = tx.createNode( book );
            book2.setProperty( "author", "E. M. Curley" );
            book2.setProperty( "title", "Descartes Against the Skeptics" );
            book2id = book2.getId();
            tx.commit();
        }

        LongHashSet ids = newSetWith( book2id );
        assertQueryFindsIds( db, true, "books", "title:Descartes", ids );
    }

    @Test
    public void mustIndexNodesByCorrectProperties()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( "a", "b", "c", "d", "e", "f" ) ) ).close();
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( "e", "value" );
            nodeId = node.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "e:value", nodeId );
    }

    @Test
    public void queryingIndexInPopulatingStateMustBlockUntilIndexIsOnline()
    {
        db = createDatabase();
        long nodeCount = 10_000;
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < nodeCount; i++ )
            {

                tx.createNode( LABEL ).setProperty( PROP, "value" );
            }
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( format( QUERY_NODES, "nodes", "value" ) );
                  Stream<Map<String,Object>> stream = result.stream() )
            {
                assertThat( stream.count(), is( nodeCount ) );
            }
            tx.commit();
        }
    }

    @Test
    public void queryingIndexInPopulatingStateMustBlockUntilIndexIsOnlineEvenWhenTransactionHasState()
    {
        db = createDatabase();
        long nodeCount = 10_000;
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < nodeCount; i++ )
            {

                tx.createNode( LABEL ).setProperty( PROP, "value" );
            }
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.createNode( LABEL ).setProperty( PROP, "value" );
            try ( Result result = tx.execute( format( QUERY_NODES, "nodes", "value" ) );
                  Stream<Map<String,Object>> stream = result.stream() )
            {
                assertThat( stream.count(), is( nodeCount + 1 ) );
            }
            tx.commit();
        }
    }

    @Test
    public void queryingIndexInTransactionItWasCreatedInMustThrow()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            expectedException.expect( QueryExecutionException.class );
            tx.execute( format( QUERY_NODES, "nodes", "value" ) ).next();
        }
    }

    @Test
    public void queryResultsMustNotIncludeNodesDeletedInOtherConcurrentlyCommittedTransactions() throws Exception
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeIdA;
        long nodeIdB;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node nodeA = tx.createNode( LABEL );
            nodeA.setProperty( PROP, "value" );
            nodeIdA = nodeA.getId();
            Node nodeB = tx.createNode( LABEL );
            nodeB.setProperty( PROP, "value" );
            nodeIdB = nodeB.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( format( QUERY_NODES, "nodes", "value" ) ) )
            {
                ThreadTestUtils.forkFuture( () ->
                {
                    try ( Transaction forkedTx = db.beginTx() )
                    {
                        tx.getNodeById( nodeIdA ).delete();
                        tx.getNodeById( nodeIdB ).delete();
                        forkedTx.commit();
                    }
                    return null;
                } ).get();
                assertThat( result.stream().count(), is( 0L ) );
            }
            tx.commit();
        }
    }

    @Test
    public void queryResultsMustNotIncludeRelationshipsDeletedInOtherConcurrentlyCommittedTransactions() throws Exception
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relIdA;
        long relIdB;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship relA = node.createRelationshipTo( node, REL );
            relA.setProperty( PROP, "value" );
            relIdA = relA.getId();
            Relationship relB = node.createRelationshipTo( node, REL );
            relB.setProperty( PROP, "value" );
            relIdB = relB.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( format( QUERY_RELS, "rels", "value" ) ) )
            {
                ThreadTestUtils.forkFuture( () ->
                {
                    try ( Transaction forkedTx = db.beginTx() )
                    {
                        tx.getRelationshipById( relIdA ).delete();
                        tx.getRelationshipById( relIdB ).delete();
                        forkedTx.commit();
                    }
                    return null;
                } ).get();
                assertThat( result.stream().count(), is( 0L ) );
            }
            tx.commit();
        }
    }

    @Test
    public void queryResultsMustNotIncludeNodesDeletedInThisTransaction()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeIdA;
        long nodeIdB;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node nodeA = tx.createNode( LABEL );
            nodeA.setProperty( PROP, "value" );
            nodeIdA = nodeA.getId();
            Node nodeB = tx.createNode( LABEL );
            nodeB.setProperty( PROP, "value" );
            nodeIdB = nodeB.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getNodeById( nodeIdA ).delete();
            tx.getNodeById( nodeIdB ).delete();
            try ( Result result = tx.execute( format( QUERY_NODES, "nodes", "value" ) ) )
            {
                assertThat( result.stream().count(), is( 0L ) );
            }
            tx.commit();
        }
    }

    @Test
    public void queryResultsMustNotIncludeRelationshipsDeletedInThisTransaction()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relIdA;
        long relIdB;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship relA = node.createRelationshipTo( node, REL );
            relA.setProperty( PROP, "value" );
            relIdA = relA.getId();
            Relationship relB = node.createRelationshipTo( node, REL );
            relB.setProperty( PROP, "value" );
            relIdB = relB.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getRelationshipById( relIdA ).delete();
            tx.getRelationshipById( relIdB ).delete();
            try ( Result result = tx.execute( format( QUERY_RELS, "rels", "value" ) ) )
            {
                assertThat( result.stream().count(), is( 0L ) );
            }
            tx.commit();
        }
    }

    @Test
    public void queryResultsMustIncludeNodesAddedInThisTransaction()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        awaitIndexesOnline();
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = tx.createNode( LABEL );
            node.setProperty( PROP, "value" );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "value", newSetWith( node.getId() ) );
    }

    @Test
    public void queryResultsMustIncludeRelationshipsAddedInThisTransaction()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        awaitIndexesOnline();
        Relationship relationship;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            relationship = node.createRelationshipTo( node, REL );
            relationship.setProperty( PROP, "value" );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "value", newSetWith( relationship.getId() ) );
    }

    @Test
    public void queryResultsMustIncludeNodesWithPropertiesAddedToBeIndexed()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            nodeId = tx.createNode( LABEL ).getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getNodeById( nodeId ).setProperty( PROP, "value" );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "prop:value", nodeId );
    }

    @Test
    public void queryResultsMustIncludeRelationshipsWithPropertiesAddedToBeIndexed()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship rel = node.createRelationshipTo( node, REL );
            relId = rel.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Relationship rel = tx.getRelationshipById( relId );
            rel.setProperty( PROP, "value" );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "prop:value", relId );
    }

    @Test
    public void queryResultsMustIncludeNodesWithLabelsModifedToBeIndexed()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            node.setProperty( PROP, "value" );
            nodeId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.addLabel( LABEL );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "value", nodeId );
    }

    @Test
    public void queryResultsMustIncludeUpdatedValueOfChangedNodeProperties()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "primo" );
            nodeId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getNodeById( nodeId ).setProperty( PROP, "secundo" );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo" );
        assertQueryFindsIds( db, true, "nodes", "secundo", nodeId );
    }

    @Test
    public void queryResultsMustIncludeUpdatedValuesOfChangedRelationshipProperties()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship rel = node.createRelationshipTo( node, REL );
            rel.setProperty( PROP, "primo" );
            relId = rel.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getRelationshipById( relId ).setProperty( PROP, "secundo" );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo" );
        assertQueryFindsIds( db, false, "rels", "secundo", relId );
    }

    @Test
    public void queryResultsMustNotIncludeNodesWithRemovedIndexedProperties()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "value" );
            nodeId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getNodeById( nodeId ).removeProperty( PROP );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "value" );
    }

    @Test
    public void queryResultsMustNotIncludeRelationshipsWithRemovedIndexedProperties()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship rel = node.createRelationshipTo( node, REL );
            rel.setProperty( PROP, "value" );
            relId = rel.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getRelationshipById( relId ).removeProperty( PROP );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "value" );
    }

    @Test
    public void queryResultsMustNotIncludeNodesWithRemovedIndexedLabels()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "value" );
            nodeId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.getNodeById( nodeId ).removeLabel( LABEL );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "nodes" );
    }

    @Test
    public void queryResultsMustIncludeOldNodePropertyValuesWhenModificationsAreUndone()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "primo" );
            nodeId = node.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo", nodeId );
        assertQueryFindsIds( db, true, "nodes", "secundo" );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( PROP, "secundo" );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo" );
        assertQueryFindsIds( db, true, "nodes", "secundo", nodeId );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( PROP, "primo" );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo", nodeId );
        assertQueryFindsIds( db, true, "nodes", "secundo" );
    }

    @Test
    public void queryResultsMustIncludeOldRelationshipPropertyValuesWhenModificationsAreUndone()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship rel = node.createRelationshipTo( node, REL );
            rel.setProperty( PROP, "primo" );
            relId = rel.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo", relId );
        assertQueryFindsIds( db, false, "rels", "secundo" );
        try ( Transaction tx = db.beginTx() )
        {
            Relationship rel = tx.getRelationshipById( relId );
            rel.setProperty( PROP, "secundo" );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo" );
        assertQueryFindsIds( db, false, "rels", "secundo", relId );
        try ( Transaction tx = db.beginTx() )
        {
            Relationship rel = tx.getRelationshipById( relId );
            rel.setProperty( PROP, "primo" );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo", relId );
        assertQueryFindsIds( db, false, "rels", "secundo" );
    }

    @Test
    public void queryResultsMustIncludeOldNodePropertyValuesWhenRemovalsAreUndone()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "primo" );
            nodeId = node.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo", nodeId );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.removeProperty( PROP );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo" );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.setProperty( PROP, "primo" );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo", nodeId );
    }

    @Test
    public void queryResultsMustIncludeOldRelationshipPropertyValuesWhenRemovalsAreUndone()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        long relId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            Relationship rel = node.createRelationshipTo( node, REL );
            rel.setProperty( PROP, "primo" );
            relId = rel.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo", relId );
        try ( Transaction tx = db.beginTx() )
        {
            Relationship rel = tx.getRelationshipById( relId );
            rel.removeProperty( PROP );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo" );
        try ( Transaction tx = db.beginTx() )
        {
            Relationship rel = tx.getRelationshipById( relId );
            rel.setProperty( PROP, "primo" );
            tx.commit();
        }
        assertQueryFindsIds( db, false, "rels", "primo", relId );
    }

    @Test
    public void queryResultsMustIncludeNodesWhenNodeLabelRemovalsAreUndone()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long nodeId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "primo" );
            nodeId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.removeLabel( LABEL );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo" );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.getNodeById( nodeId );
            node.addLabel( LABEL );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "primo", nodeId );
    }

    @Test
    public void queryResultsFromTransactionStateMustSortTogetherWithResultFromBaseIndex()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long firstId;
        long secondId;
        long thirdId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node first = tx.createNode( LABEL );
            first.setProperty( PROP, "God of War" );
            firstId = first.getId();
            Node third = tx.createNode( LABEL );
            third.setProperty( PROP, "God Wars: Future Past" );
            thirdId = third.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node second = tx.createNode( LABEL );
            second.setProperty( PROP, "God of War III Remastered" );
            secondId = second.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "god of war", firstId, secondId, thirdId );
    }

    @Test
    public void queryResultsMustBeOrderedByScore()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        long firstId;
        long secondId;
        long thirdId;
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "dude sweet" );
            firstId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "dude sweet dude sweet" );
            secondId = node.getId();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "dude sweet dude dude dude sweet" );
            thirdId = node.getId();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "dude", thirdId, secondId, firstId );
    }

    @Test
    public void queryingDroppedIndexForNodesInDroppingTransactionMustThrow()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( DROP, "nodes" ) ).close();
            expectedException.expect( QueryExecutionException.class );
            tx.execute( format( QUERY_NODES, "nodes", "blabla" ) ).next();
        }
    }

    @Test
    public void queryingDroppedIndexForRelationshipsInDroppingTransactionMustThrow()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( DROP, "rels" ) ).close();
            expectedException.expect( QueryExecutionException.class );
            tx.execute( format( QUERY_RELS, "rels", "blabla" ) ).next();
        }
    }

    @Test
    public void creatingAndDroppingIndexesInSameTransactionMustNotThrow()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.execute( format( DROP, "nodes" ) ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleRelationshipIndex( tx );
            tx.execute( format( DROP, "rels" ) ).close();
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            assertFalse( tx.schema().getIndexes().iterator().hasNext() );
            tx.commit();
        }
    }

    @Test
    public void eventuallyConsistenIndexMustNotIncludeEntitiesAddedInTransaction()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) ).close();
            tx.execute( format( RELATIONSHIP_CREATE, "rels", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT ) ).close();
            tx.commit();
        }
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "value" );
            node.createRelationshipTo( node, REL ).setProperty( PROP, "value" );
        }

        assertQueryFindsIds( db, true, "nodes", "value" );
        assertQueryFindsIds( db, false, "rels", "value" );
        try ( Transaction transaction = db.beginTx() )
        {
            transaction.execute( AWAIT_REFRESH ).close();
            transaction.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "value" );
        assertQueryFindsIds( db, false, "rels", "value" );
    }

    @Test
    public void prefixedFulltextIndexSettingMustBeRecognized()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT_PREFIXED ) )
                    .close();
            tx.execute( format( RELATIONSHIP_CREATE, "rels", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + EVENTUALLY_CONSISTENT_PREFIXED ) )
                    .close();
            tx.commit();
        }
        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            Iterator<IndexDefinition> iterator = tx.schema().getIndexes().iterator();
            while ( iterator.hasNext() )
            {
                IndexDefinition index = iterator.next();
                Map<IndexSetting,Object> indexConfiguration = index.getIndexConfiguration();
                Object eventuallyConsistentObj = indexConfiguration.get( IndexSettingImpl.FULLTEXT_EVENTUALLY_CONSISTENT );
                assertNotNull( eventuallyConsistentObj );
                assertThat( eventuallyConsistentObj, instanceOf( Boolean.class ) );
                assertEquals( true, eventuallyConsistentObj );
            }
            tx.commit();
        }
    }

    @Test
    public void prefixedFulltextIndexSettingMustBeRecognizedTogetherWithNonPrefixed()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            String mixedPrefixConfig = ", {`fulltext.analyzer`: 'english', eventually_consistent: 'true'}";
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + mixedPrefixConfig ) )
                    .close();
            tx.execute( format( RELATIONSHIP_CREATE, "rels", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + mixedPrefixConfig ) )
                    .close();
            tx.commit();
        }
        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            Iterator<IndexDefinition> iterator = tx.schema().getIndexes().iterator();
            while ( iterator.hasNext() )
            {
                IndexDefinition index = iterator.next();
                Map<IndexSetting,Object> indexConfiguration = index.getIndexConfiguration();
                Object eventuallyConsistentObj = indexConfiguration.get( IndexSettingImpl.FULLTEXT_EVENTUALLY_CONSISTENT );
                assertNotNull( eventuallyConsistentObj );
                assertThat( eventuallyConsistentObj, instanceOf( Boolean.class ) );
                assertEquals( true, eventuallyConsistentObj );
                Object analyzerObj = indexConfiguration.get( IndexSettingImpl.FULLTEXT_ANALYZER );
                assertNotNull( analyzerObj );
                assertThat( analyzerObj, instanceOf( String.class ) );
                assertEquals( "english", analyzerObj );
            }
            tx.commit();
        }
    }

    @Test
    public void mustThrowOnDuplicateFulltextIndexSetting()
    {
        db = createDatabase();
        String duplicateConfig = ", {`fulltext.analyzer`: 'english', analyzer: 'swedish'}";

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) + duplicateConfig ) ).close();
            fail( "Expected to fail" );
        }
        catch ( QueryExecutionException e )
        {
            assertThat( e.getMessage(), containsString( "Config setting was specified more than once, 'analyzer'." ) );
            Throwable rootCause = getRootCause( e );
            assertThat( rootCause, instanceOf( IllegalArgumentException.class ) );
        }

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( RELATIONSHIP_CREATE, "rels", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) + duplicateConfig ) ).close();
            fail( "Expected to fail" );
        }
        catch ( QueryExecutionException e )
        {
            assertThat( e.getMessage(), containsString( "Config setting was specified more than once, 'analyzer'." ) );
            Throwable rootCause = getRootCause( e );
            assertThat( rootCause, instanceOf( IllegalArgumentException.class ) );
        }
    }

    @Test
    public void transactionStateMustNotPreventIndexUpdatesFromBeingApplied() throws Exception
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        awaitIndexesOnline();
        LongHashSet nodeIds = new LongHashSet();
        LongHashSet relIds = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "value" );
            Relationship rel = node.createRelationshipTo( node, REL );
            rel.setProperty( PROP, "value" );
            nodeIds.add( node.getId() );
            relIds.add( rel.getId() );

            ExecutorService executor = cleanup.add( Executors.newSingleThreadExecutor() );
            executor.submit( () ->
            {
                try ( Transaction forkedTx = db.beginTx() )
                {
                    Node node2 = forkedTx.createNode( LABEL );
                    node2.setProperty( PROP, "value" );
                    Relationship rel2 = node2.createRelationshipTo( node2, REL );
                    rel2.setProperty( PROP, "value" );
                    nodeIds.add( node2.getId() );
                    relIds.add( rel2.getId() );
                    forkedTx.commit();
                }
            }).get();
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "value", nodeIds );
        assertQueryFindsIds( db, false, "rels", "value", relIds );
    }

    @Test
    public void dropMustNotApplyToRegularSchemaIndexes()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().indexFor( LABEL ).on( PROP ).create();
            tx.commit();
        }
        awaitIndexesOnline();
        String schemaIndexName;
        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( "call db.indexes()" ) )
            {
                assertTrue( result.hasNext() );
                schemaIndexName = result.next().get( "name" ).toString();
            }
            expectedException.expect( QueryExecutionException.class );
            tx.execute( format( DROP, schemaIndexName ) ).close();
        }
    }

    @Test
    public void fulltextIndexMustNotBeAvailableForRegularIndexSeeks()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        String valueToQueryFor = "value to query for";
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            List<Value> values = generateRandomSimpleValues();
            for ( Value value : values )
            {
                tx.createNode( LABEL ).setProperty( PROP, value.asObject() );
            }
            tx.createNode( LABEL ).setProperty( PROP, valueToQueryFor );
            tx.commit();
        }
        try ( Transaction transaction = db.beginTx() )
        {
            Map<String,Object> params = new HashMap<>();
            params.put( "prop", valueToQueryFor );
            try ( Result result = transaction.execute( "profile match (n:" + LABEL.name() + ") where n." + PROP + " = $prop return n", params ) )
            {
                assertNoIndexSeeks( result );
            }
            try ( Result result = transaction.execute( "cypher 3.5 profile match (n:" + LABEL.name() + ") where n." + PROP + " = $prop return n", params ) )
            {
                assertNoIndexSeeks( result );
            }
            transaction.commit();
        }
    }

    @Test
    public void fulltextIndexMustNotBeAvailableForRegularIndexSeeksAfterShutDown()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        managementService.shutdown();
        db = createDatabase();
        String valueToQueryFor = "value to query for";
        awaitIndexesOnline();
        try ( Transaction tx = db.beginTx() )
        {
            List<Value> values = generateRandomSimpleValues();
            for ( Value value : values )
            {
                tx.createNode( LABEL ).setProperty( PROP, value.asObject() );
            }
            tx.createNode( LABEL ).setProperty( PROP, valueToQueryFor );
            tx.commit();
        }
        try ( Transaction transaction = db.beginTx() )
        {
            Map<String,Object> params = new HashMap<>();
            params.put( "prop", valueToQueryFor );
            try ( Result result = transaction.execute( "profile match (n:" + LABEL.name() + ") where n." + PROP + " = $prop return n", params ) )
            {
                assertNoIndexSeeks( result );
            }
            try ( Result result = transaction.execute( "cypher 3.5 profile match (n:" + LABEL.name() + ") where n." + PROP + " = $prop return n", params ) )
            {
                assertNoIndexSeeks( result );
            }
            transaction.commit();
        }
    }

    @Test
    public void nodeOutputMustBeOrderedByScoreDescending()
    {
        FulltextProcedures.NodeOutput a = new FulltextProcedures.NodeOutput( null, Float.NaN );
        FulltextProcedures.NodeOutput b = new FulltextProcedures.NodeOutput( null, Float.POSITIVE_INFINITY );
        FulltextProcedures.NodeOutput c = new FulltextProcedures.NodeOutput( null, Float.MAX_VALUE );
        FulltextProcedures.NodeOutput d = new FulltextProcedures.NodeOutput( null, 1.0f );
        FulltextProcedures.NodeOutput e = new FulltextProcedures.NodeOutput( null, Float.MIN_NORMAL );
        FulltextProcedures.NodeOutput f = new FulltextProcedures.NodeOutput( null, Float.MIN_VALUE );
        FulltextProcedures.NodeOutput g = new FulltextProcedures.NodeOutput( null, 0.0f );
        FulltextProcedures.NodeOutput h = new FulltextProcedures.NodeOutput( null, -1.0f );
        FulltextProcedures.NodeOutput i = new FulltextProcedures.NodeOutput( null, Float.NEGATIVE_INFINITY );
        FulltextProcedures.NodeOutput[] expectedOrder = new FulltextProcedures.NodeOutput[] {a, b, c, d, e, f, g, h, i};
        FulltextProcedures.NodeOutput[] array = expectedOrder.clone();

        for ( int counter = 0; counter < 10; counter++ )
        {
            ArrayUtils.shuffle( array );
            Arrays.sort( array );
            assertArrayEquals( expectedOrder, array );
        }
    }

    @Test
    public void relationshipOutputMustBeOrderedByScoreDescending()
    {
        FulltextProcedures.RelationshipOutput a = new FulltextProcedures.RelationshipOutput( null, Float.NaN );
        FulltextProcedures.RelationshipOutput b = new FulltextProcedures.RelationshipOutput( null, Float.POSITIVE_INFINITY );
        FulltextProcedures.RelationshipOutput c = new FulltextProcedures.RelationshipOutput( null, Float.MAX_VALUE );
        FulltextProcedures.RelationshipOutput d = new FulltextProcedures.RelationshipOutput( null, 1.0f );
        FulltextProcedures.RelationshipOutput e = new FulltextProcedures.RelationshipOutput( null, Float.MIN_NORMAL );
        FulltextProcedures.RelationshipOutput f = new FulltextProcedures.RelationshipOutput( null, Float.MIN_VALUE );
        FulltextProcedures.RelationshipOutput g = new FulltextProcedures.RelationshipOutput( null, 0.0f );
        FulltextProcedures.RelationshipOutput h = new FulltextProcedures.RelationshipOutput( null, -1.0f );
        FulltextProcedures.RelationshipOutput i = new FulltextProcedures.RelationshipOutput( null, Float.NEGATIVE_INFINITY );
        FulltextProcedures.RelationshipOutput[] expectedOrder = new FulltextProcedures.RelationshipOutput[] {a, b, c, d, e, f, g, h, i};
        FulltextProcedures.RelationshipOutput[] array = expectedOrder.clone();

        for ( int counter = 0; counter < 10; counter++ )
        {
            ArrayUtils.shuffle( array );
            Arrays.sort( array );
            assertArrayEquals( expectedOrder, array );
        }
    }

    @Test
    public void awaitIndexProcedureMustWorkOnIndexNames()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < 1000; i++ )
            {
                Node node = tx.createNode( LABEL );
                node.setProperty( PROP, "value" );
                Relationship rel = node.createRelationshipTo( node, REL );
                rel.setProperty( PROP, "value" );
            }
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            createSimpleRelationshipIndex( tx );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( DB_AWAIT_INDEX, "nodes" ) ).close();
            tx.execute( format( DB_AWAIT_INDEX, "rels" ) ).close();
            tx.commit();
        }
    }

    @Test
    public void mustBePossibleToDropFulltextIndexByNameForWhichNormalIndexExistWithMatchingSchema()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( "CREATE INDEX FOR (n:Person) ON (n.name)" ).close();
            tx.execute( "call db.index.fulltext.createNodeIndex('nameIndex', ['Person'], ['name'])" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            // This must not throw:
            tx.execute( "call db.index.fulltext.drop('nameIndex')" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            assertThat( single( tx.schema().getIndexes() ).getName(), is( not( "nameIndex" ) ) );
            tx.commit();
        }
    }

    @Test
    public void fulltextIndexesMustNotPreventNormalSchemaIndexesFromBeingDropped()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( "CREATE INDEX FOR (n:Person) ON (n.name)" ).close();
            tx.execute( "call db.index.fulltext.createNodeIndex('nameIndex', ['Person'], ['name'])" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            // This must not throw:
            tx.execute( "DROP INDEX ON :Person(name)" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            assertThat( single( tx.schema().getIndexes() ).getName(), is( "nameIndex" ) );
            tx.commit();
        }
    }

    @Test
    public void creatingNormalIndexWithFulltextProviderMustThrow()
    {
        db = createDatabase();
        assertThat( FulltextIndexProviderFactory.DESCRIPTOR.name(), is( "fulltext-1.0" ) ); // Sanity check that this test is up to date.

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( "call db.createIndex( \"MyIndex\", ['User'], ['searchableString'], \"" + FulltextIndexProviderFactory.DESCRIPTOR.name() + "\" );" )
                    .close();
            tx.commit();
        }
        catch ( QueryExecutionException e )
        {
            assertThat( e.getMessage(), containsString(
                    "Could not create index with specified index provider 'fulltext-1.0'. To create fulltext index, please use 'db.index.fulltext" +
                            ".createNodeIndex' or 'db.index.fulltext.createRelationshipIndex'." ) );
        }

        try ( Transaction tx = db.beginTx() )
        {
            long indexCount = tx.execute( DB_INDEXES ).stream().count();
            assertThat( indexCount, is( 0L ) );
            tx.commit();
        }
    }

    @Test
    public void mustSupportWildcardEndsLikeStartsWith()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        LongHashSet ids = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "abcdef" );
            ids.add( node.getId() );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "abcxyz" );
            ids.add( node.getId() );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "abc*", ids );
    }

    @Test
    public void mustSupportWildcardBeginningsLikeEndsWith()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        LongHashSet ids = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "defabc" );
            ids.add( node.getId() );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "xyzabc" );
            ids.add( node.getId() );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "*abc", ids );
    }

    @Test
    public void mustSupportWildcardBeginningsAndEndsLikeContains()
    {
        db = createDatabase();
        try ( Transaction tx = db.beginTx() )
        {
            createSimpleNodesIndex( tx );
            tx.commit();
        }
        LongHashSet ids = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "defabcdef" );
            ids.add( node.getId() );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( LABEL );
            node.setProperty( PROP, "xyzabcxyz" );
            ids.add( node.getId() );
            tx.commit();
        }
        assertQueryFindsIds( db, true, "nodes", "*abc*", ids );
    }

    @Test
    public void mustMatchCaseInsensitiveWithStandardAnalyzer()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'A'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'B'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'C'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'b'}))" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( "Label" ), asCypherStringsList( "id" ) + ", {analyzer: 'standard'}" ) ).close();
            tx.commit();
        }
        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "A" ) ) )
            {
                assertThat( result.stream().count(), is( 0L ) ); // The letter 'A' is a stop-word in English, so it is not indexed.
            }
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "B" ) ) )
            {
                assertThat( result.stream().count(), is( 2000L ) ); // Both upper- and lower-case 'B' nodes.
            }
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "C" ) ) )
            {
                assertThat( result.stream().count(), is( 1000L ) ); // We only have upper-case 'C' nodes.
            }
            tx.commit();
        }
    }

    @Test
    public void mustMatchCaseInsensitiveWithSimpleAnalyzer()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'A'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'B'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'C'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'b'}))" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( "Label" ), asCypherStringsList( "id" ) + ", {analyzer: 'simple'}" ) ).close();
            tx.commit();
        }
        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "A" ) ) )
            {
                assertThat( result.stream().count(), is( 1000L ) ); // We only have upper-case 'A' nodes.
            }
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "B" ) ) )
            {
                assertThat( result.stream().count(), is( 2000L ) ); // Both upper- and lower-case 'B' nodes.
            }
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "C" ) ) )
            {
                assertThat( result.stream().count(), is( 1000L ) ); // We only have upper-case 'C' nodes.
            }
            tx.commit();
        }
    }

    @Test
    public void mustMatchCaseInsensitiveWithDefaultAnalyzer()
    {
        db = createDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'A'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'B'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'C'}))" ).close();
            tx.execute( "foreach (x in range (1,1000) | create (n:Label {id:'b'}))" ).close();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( "Label" ), asCypherStringsList( "id" ) ) ).close();
            tx.commit();
        }
        awaitIndexesOnline();

        try ( Transaction tx = db.beginTx() )
        {
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "A" ) ) )
            {
                assertThat( result.stream().count(), is( 1000L ) ); // We only have upper-case 'A' nodes.
            }
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "B" ) ) )
            {
                assertThat( result.stream().count(), is( 2000L ) ); // Both upper- and lower-case 'B' nodes.
            }
            try ( Result result = tx.execute( format( QUERY_NODES, "myindex", "C" ) ) )
            {
                assertThat( result.stream().count(), is( 1000L ) ); // We only have upper-case 'C' nodes.
            }
            tx.commit();
        }
    }

    @Test
    public void makeSureFulltextIndexDoesNotBlockSchemaIndexOnSameSchemaPattern()
    {
        db = createDatabase();

        final Label label = Label.label( "label" );
        final String prop = "prop";
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( label.name() ), asCypherStringsList( prop ) ) );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( DB_AWAIT_INDEX, "myindex" ) );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().indexFor( label ).on( prop ).create();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().awaitIndexesOnline( 1, TimeUnit.HOURS );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            assertEquals( 2, Iterables.count( tx.schema().getIndexes() ) );
            tx.commit();
        }
    }

    @Test
    public void makeSureSchemaIndexDoesNotBlockFulltextIndexOnSameSchemaPattern()
    {
        db = createDatabase();

        final Label label = Label.label( "label" );
        final String prop = "prop";
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().indexFor( label ).on( prop ).create();
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().awaitIndexesOnline( 1, TimeUnit.HOURS );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( label.name() ), asCypherStringsList( prop ) ) );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            tx.execute( format( DB_AWAIT_INDEX, "myindex" ) );
            tx.commit();
        }
        try ( Transaction tx = db.beginTx() )
        {
            assertEquals( 2, Iterables.count( tx.schema().getIndexes() ) );
            tx.commit();
        }
    }

    @Test
    public void shouldNotBePossibleToCreateIndexWithDuplicateProperty()
    {
        db = createDatabase();

        final Exception e = assertThrows( Exception.class, () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( "Label" ), asCypherStringsList( "id", "id" ) ) );
            }
        } );
        final Throwable cause = getRootCause( e );
        assertThat( cause, instanceOf( RepeatedPropertyInSchemaException.class ) );
    }

    @Test
    public void shouldNotBePossibleToCreateIndexWithDuplicateLabel()
    {
        db = createDatabase();

        final Exception e = assertThrows( Exception.class, () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                tx.execute( format( NODE_CREATE, "myindex", asCypherStringsList( "Label", "Label" ), asCypherStringsList( "id" ) ) );
            }
        } );
        final Throwable cause = getRootCause( e );
        assertThat( cause, instanceOf( RepeatedLabelInSchemaException.class ) );
    }

    @Test
    public void shouldNotBePossibleToCreateIndexWithDuplicateRelType()
    {
        db = createDatabase();

        final Exception e = assertThrows( Exception.class, () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                tx.execute( format( RELATIONSHIP_CREATE, "myindex", asCypherStringsList( "RelType", "RelType" ), asCypherStringsList( "id" ) ) );
            }
        } );
        final Throwable cause = getRootCause( e );
        assertThat( cause, instanceOf( RepeatedRelationshipTypeInSchemaException.class ) );
    }

    @Test
    public void attemptingToIndexOnPropertyUsedForInternalReferenceMustThrow()
    {
        db = createDatabase();

        var e = assertThrows( Exception.class, () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                tx.execute( format( NODE_CREATE, "myindex",
                        asCypherStringsList( "Label" ),
                        asCypherStringsList( LuceneFulltextDocumentStructure.FIELD_ENTITY_ID ) ) )
                        .close();
                tx.commit();
            }
        });
        assertThat( e.getMessage(), containsString( LuceneFulltextDocumentStructure.FIELD_ENTITY_ID ) );
    }

    private void assertNoIndexSeeks( Result result )
    {
        assertThat( result.stream().count(), is( 1L ) );
        String planDescription = result.getExecutionPlanDescription().toString();
        assertThat( planDescription, containsString( "NodeByLabel" ) );
        assertThat( planDescription, not( containsString( "IndexSeek" ) ) );
    }

    private GraphDatabaseAPI createDatabase()
    {
        managementService = builder.build();
        cleanup.add( managementService );
        return (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
    }

    private void awaitIndexesOnline()
    {
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
            tx.commit();
        }
    }

    static void assertQueryFindsIds( GraphDatabaseService db, boolean queryNodes, String index, String query, long... ids )
    {
        try ( Transaction tx = db.beginTx() )
        {
            String queryCall = queryNodes ? QUERY_NODES : QUERY_RELS;
            Result result = tx.execute( format( queryCall, index, query ) );
            int num = 0;
            Double score = Double.MAX_VALUE;
            while ( result.hasNext() )
            {
                Map entry = result.next();
                Long nextId = ((Entity) entry.get( queryNodes ? NODE : RELATIONSHIP )).getId();
                Double nextScore = (Double) entry.get( SCORE );
                assertThat( nextScore, lessThanOrEqualTo( score ) );
                score = nextScore;
                if ( num < ids.length )
                {
                    assertEquals( format( "Result returned id %d, expected %d", nextId, ids[num] ), ids[num], nextId.longValue() );
                }
                else
                {
                    fail( format( "Result returned id %d, which is beyond the number of ids (%d) that were expected.", nextId, ids.length ) );
                }
                num++;
            }
            assertEquals( "Number of results differ from expected", ids.length, num );
            tx.commit();
        }
    }

    static void assertQueryFindsIds( GraphDatabaseService db, boolean queryNodes, String index, String query, LongHashSet ids )
    {
        ids = new LongHashSet( ids ); // Create a defensive copy, because we're going to modify this instance.
        String queryCall = queryNodes ? QUERY_NODES : QUERY_RELS;
        long[] expectedIds = ids.toArray();
        MutableLongSet actualIds = new LongHashSet();
        try ( Transaction tx = db.beginTx() )
        {
            LongFunction<Entity> getEntity = queryNodes ? tx::getNodeById : tx::getRelationshipById;
            Result result = tx.execute( format( queryCall, index, query ) );
            Double score = Double.MAX_VALUE;
            while ( result.hasNext() )
            {
                Map entry = result.next();
                long nextId = ((Entity) entry.get( queryNodes ? NODE : RELATIONSHIP )).getId();
                Double nextScore = (Double) entry.get( SCORE );
                assertThat( nextScore, lessThanOrEqualTo( score ) );
                score = nextScore;
                actualIds.add( nextId );
                if ( !ids.remove( nextId ) )
                {
                    String msg = "This id was not expected: " + nextId;
                    failQuery( getEntity, index, query, ids, expectedIds, actualIds, msg );
                }
            }
            if ( !ids.isEmpty() )
            {
                String msg = "Not all expected ids were found: " + ids;
                failQuery( getEntity, index, query, ids, expectedIds, actualIds, msg );
            }
            tx.commit();
        }
    }

    private static void failQuery( LongFunction<Entity> getEntity, String index, String query, MutableLongSet ids, long[] expectedIds, MutableLongSet actualIds,
            String msg )
    {
        StringBuilder message = new StringBuilder( msg ).append( '\n' );
        MutableLongIterator itr = ids.longIterator();
        while ( itr.hasNext() )
        {
            long id = itr.next();
            Entity entity = getEntity.apply( id );
            message.append( '\t' ).append( entity ).append( entity.getAllProperties() ).append( '\n' );
        }
        message.append( "for query: '" ).append( query ).append( "'\nin index: " ).append( index ).append( '\n' );
        message.append( "all expected ids: " ).append( Arrays.toString( expectedIds ) ).append( '\n' );
        message.append( "actual ids: " ).append( actualIds );
        itr = actualIds.longIterator();
        while ( itr.hasNext() )
        {
            long id = itr.next();
            Entity entity = getEntity.apply( id );
            message.append( "\n\t" ).append( entity ).append( entity.getAllProperties() );
        }
        fail( message.toString() );
    }

    private List<Value> generateRandomNonStringValues()
    {
        Predicate<Value> nonString = v -> v.valueGroup() != ValueGroup.TEXT;
        return generateRandomValues( nonString );
    }

    private List<Value> generateRandomSimpleValues()
    {
        EnumSet<ValueGroup> simpleTypes = EnumSet.of(
                ValueGroup.BOOLEAN, ValueGroup.BOOLEAN_ARRAY, ValueGroup.NUMBER, ValueGroup.NUMBER_ARRAY );
        return generateRandomValues( v -> simpleTypes.contains( v.valueGroup() ) );
    }

    private List<Value> generateRandomValues( Predicate<Value> predicate )
    {
        int valuesToGenerate = 1000;
        RandomValues generator = RandomValues.create();
        List<Value> values = new ArrayList<>( valuesToGenerate );
        for ( int i = 0; i < valuesToGenerate; i++ )
        {
            Value value;
            do
            {
                value = generator.nextValue();
            }
            while ( !predicate.test( value ) );
            values.add( value );
        }
        return values;
    }

    private String quoteValueForQuery( Value value )
    {
        return QueryParserUtil.escape( value.prettyPrint() ).replace( "\\", "\\\\" ).replace( "\"", "\\\"" );
    }

    private void createSimpleRelationshipIndex( Transaction tx )
    {
        tx.execute( format( RELATIONSHIP_CREATE, "rels", asCypherStringsList( REL.name() ), asCypherStringsList( PROP ) ) ).close();
    }

    private void createSimpleNodesIndex( Transaction tx )
    {
        tx.execute( format( NODE_CREATE, "nodes", asCypherStringsList( LABEL.name() ), asCypherStringsList( PROP ) ) ).close();
    }
}
