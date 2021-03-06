/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.modeshape.jcr.api.observation.Event.ALL_EVENTS;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeType;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventJournal;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.ObservationManager;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.transaction.TransactionManager;
import org.apache.jackrabbit.test.api.observation.AddEventListenerTest;
import org.apache.jackrabbit.test.api.observation.EventIteratorTest;
import org.apache.jackrabbit.test.api.observation.EventTest;
import org.apache.jackrabbit.test.api.observation.GetRegisteredEventListenersTest;
import org.apache.jackrabbit.test.api.observation.NodeAddedTest;
import org.apache.jackrabbit.test.api.observation.NodeMovedTest;
import org.apache.jackrabbit.test.api.observation.NodeRemovedTest;
import org.apache.jackrabbit.test.api.observation.NodeReorderTest;
import org.apache.jackrabbit.test.api.observation.PropertyAddedTest;
import org.apache.jackrabbit.test.api.observation.PropertyChangedTest;
import org.apache.jackrabbit.test.api.observation.PropertyRemovedTest;
import org.apache.jackrabbit.test.api.observation.WorkspaceOperationTest;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.modeshape.common.FixFor;
import org.modeshape.common.util.FileUtil;
import org.modeshape.jcr.JcrObservationManager.JcrEventBundle;
import org.modeshape.jcr.api.observation.PropertyEvent;
import org.modeshape.jcr.api.value.DateTime;
import org.modeshape.jcr.value.Name;

/**
 * The {@link JcrObservationManager} test class.
 */
public final class JcrObservationManagerTest extends SingleUseAbstractTest {

    private static final String LOCK_MIXIN = "mix:lockable"; // extends referenceable
    private static final String LOCK_OWNER = "jcr:lockOwner"; // property
    private static final String LOCK_IS_DEEP = "jcr:lockIsDeep"; // property
    // private static final String NT_BASE = "nt:base";
    private static final String REF_MIXIN = "mix:referenceable";
    private static final String UNSTRUCTURED = "nt:unstructured";
    private static final String USER_ID = "superuser";

    protected static final String WORKSPACE = "ws1";
    protected static final String WORKSPACE2 = "ws2";

    private Node testRootNode;

    @BeforeClass
    public static void beforeAll() {
        // Initialize PicketBox ...
        JaasTestUtil.initJaas("security/jaas.conf.xml");
    }
    @Override
    protected boolean startRepositoryAutomatically() {
        return false;
    }

    @Override
    @Before
    public void beforeEach() throws Exception {
        super.beforeEach();
        FileUtil.delete("target/obs_journal");
        startRepositoryWithConfigurationFrom("config/repo-config-observation.json");
        session = login(WORKSPACE);

        this.testRootNode = this.session.getRootNode().addNode("testroot", UNSTRUCTURED);
        save();
    }


    SimpleListener addListener( int expectedEventsCount,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        return addListener(expectedEventsCount, 1, eventTypes, absPath, isDeep, uuids, nodeTypeNames, noLocal);
    }

    SimpleListener addListener( int expectedEventsCount,
                                int numIterators,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        return addListener(this.session,
                           expectedEventsCount,
                           numIterators,
                           eventTypes,
                           absPath,
                           isDeep,
                           uuids,
                           nodeTypeNames,
                           noLocal);
    }

    SimpleListener addListener( Session session,
                                int expectedEventsCount,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        return addListener(session, expectedEventsCount, 1, eventTypes, absPath, isDeep, uuids, nodeTypeNames, noLocal);
    }

    SimpleListener addListener( Session session,
                                int expectedEventsCount,
                                int numIterators,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        SimpleListener listener = new SimpleListener(expectedEventsCount, numIterators, eventTypes);
        session.getWorkspace()
               .getObservationManager()
               .addEventListener(listener, eventTypes, absPath, isDeep, uuids, nodeTypeNames, noLocal);
        return listener;
    }

    protected JcrSession login( String workspaceName ) throws RepositoryException {
        Session session = repository.login(new SimpleCredentials(USER_ID, USER_ID.toCharArray()), workspaceName);
        assertTrue(session != this.session);
        return (JcrSession)session;
    }

    void checkResults( SimpleListener listener ) {
        if (listener.getActualEventCount() != listener.getExpectedEventCount()) {
            // Wrong number ...
            StringBuilder sb = new StringBuilder(" Actual events were: ");
            for (Event event : listener.getEvents()) {
                sb.append('\n').append(event);
            }
            assertThat("Received incorrect number of events." + sb.toString(),
                       listener.getActualEventCount(),
                       is(listener.getExpectedEventCount()));
            assertThat(listener.getErrorMessage(), listener.getErrorMessage(), is(nullValue()));
        }
    }

    boolean containsPath( SimpleListener listener,
                          String path ) throws Exception {
        for (Event event : listener.getEvents()) {
            if (event.getPath().equals(path)) return true;
        }

        return false;
    }

    ObservationManager getObservationManager() throws RepositoryException {
        return this.session.getWorkspace().getObservationManager();
    }

    Node getRoot() {
        return testRootNode;
    }

    Workspace getWorkspace() {
        return this.session.getWorkspace();
    }

    void removeListener( SimpleListener listener ) throws Exception {
        this.session.getWorkspace().getObservationManager().removeEventListener(listener);
    }

    void save() throws RepositoryException {
        this.session.save();
    }

    @Test
    public void shouldNotReceiveEventIfUuidDoesNotMatch() throws Exception {
        // setup
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        n1.addMixin(REF_MIXIN);
        save();

        // register listener
        SimpleListener listener = addListener(0,
                                              Event.PROPERTY_ADDED,
                                              getRoot().getPath(),
                                              true,
                                              new String[] { UUID.randomUUID().toString() },
                                              null,
                                              false);

        // create properties
        n1.setProperty("prop1", "foo");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
    }

    @Test
    public void shouldNotReceiveEventIfNodeTypeDoesNotMatch() throws Exception {
        // setup
        Node node1 = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // register listener
        SimpleListener listener = addListener(0, ALL_EVENTS, null, false, null, new String[] {REF_MIXIN}, false);

        // create event triggers
        node1.setProperty("newProperty", "newValue"); // node1 is NOT referenceable
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
    }

    @Test
    public void shouldReceiveNodeAddedEventWhenRegisteredToReceiveAllEvents() throws Exception {
        // register listener (add + primary type property)
        SimpleListener listener = addListener(4, ALL_EVENTS, null, false, null, null, false)
                .withExpectedNodePrimaryType(UNSTRUCTURED).withExpectedNodeMixinTypes(REF_MIXIN);

        // add node
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        addedNode.addMixin(REF_MIXIN);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));
    }

    @Test
    public void shouldReceiveNodeRemovedEventWhenRegisteredToReceiveAllEvents() throws Exception {
        // add the node that will be removed
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // register listener (add + 3 property events)
        SimpleListener listener = addListener(1, ALL_EVENTS, null, false, null, null, false).withExpectedNodePrimaryType(UNSTRUCTURED);

        // remove node
        String path = addedNode.getPath();
        addedNode.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for removed node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected=" + path,
                   containsPath(listener, path));
    }

    @Test
    public void shouldReceivePropertyAddedEventWhenRegisteredToReceiveAllEvents() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        node.addMixin(REF_MIXIN);
        save();

        // register listener
        SimpleListener listener = addListener(1, ALL_EVENTS, null, false, null, null, false)
                .withExpectedNodePrimaryType(UNSTRUCTURED).withExpectedNodeMixinTypes(REF_MIXIN);

        // add the property
        Property prop1 = node.setProperty("prop1", "prop1 content");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        Event propertyAddedEvent = listener.getEvents().get(0);
        assertTrue("Path for added property is wrong: actual=" + propertyAddedEvent.getPath() + ", expected="
                   + prop1.getPath(),
                   containsPath(listener, prop1.getPath()));

        assertTrue(propertyAddedEvent instanceof PropertyEvent);
        PropertyEvent propertyEvent = (PropertyEvent) propertyAddedEvent;

        assertEquals("prop1 content", propertyEvent.getCurrentValue());
        assertEquals(1, propertyEvent.getCurrentValues().size());
        assertEquals("prop1 content", propertyEvent.getCurrentValues().get(0));
        assertFalse(propertyEvent.isMultiValue());

        assertFalse(propertyEvent.wasMultiValue());
        assertNull(propertyEvent.getPreviousValue());
        assertNull(propertyEvent.getPreviousValues());
    }

    @Test
    public void shouldReceivePropertyChangedEventWhenRegisteredToReceiveAllEvents() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop1 = node.setProperty("prop1", "prop1 content");
        save();

        // register listener
        SimpleListener listener = addListener(1, ALL_EVENTS, null, false, null, null, false).withExpectedNodePrimaryType(UNSTRUCTURED);

        // change the property
        prop1.setValue("prop1 modified content");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        Event propertyChangedEvent = listener.getEvents().get(0);
        assertTrue("Path for changed property is wrong: actual=" + propertyChangedEvent.getPath() + ", expected="
                   + prop1.getPath(),
                   containsPath(listener, prop1.getPath()));


        assertTrue(propertyChangedEvent instanceof PropertyEvent);
        PropertyEvent propertyEvent = (PropertyEvent) propertyChangedEvent;

        assertEquals("prop1 modified content", propertyEvent.getCurrentValue());
        assertEquals(1, propertyEvent.getCurrentValues().size());
        assertEquals("prop1 modified content", propertyEvent.getCurrentValues().get(0));
        assertFalse(propertyEvent.isMultiValue());

        assertFalse(propertyEvent.wasMultiValue());
        assertEquals("prop1 content", propertyEvent.getPreviousValue());
        assertEquals(1, propertyEvent.getPreviousValues().size());
        assertEquals("prop1 content", propertyEvent.getPreviousValues().get(0));
    }

    @Test
    public void shouldReceivePropertyRemovedEventWhenRegisteredToReceiveAllEvents() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop = node.setProperty("prop1", "prop1 content");
        String propPath = prop.getPath();
        save();

        // register listener
        SimpleListener listener = addListener(1, ALL_EVENTS, null, false, null, null, false).withExpectedNodePrimaryType(UNSTRUCTURED);

        // remove the property
        prop.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        Event propertyRemovedEvent = listener.getEvents().get(0);
        assertTrue("Path for removed property is wrong: actual=" + propertyRemovedEvent.getPath() + ", expected="
                   + propPath, containsPath(listener, propPath));

        assertTrue(propertyRemovedEvent instanceof PropertyEvent);
        PropertyEvent propertyEvent = (PropertyEvent) propertyRemovedEvent;

        assertEquals("prop1 content", propertyEvent.getCurrentValue());
        assertEquals(1, propertyEvent.getCurrentValues().size());
        assertEquals("prop1 content", propertyEvent.getCurrentValues().get(0));
        assertFalse(propertyEvent.isMultiValue());

        assertFalse(propertyEvent.wasMultiValue());
        assertNull(propertyEvent.getPreviousValue());
        assertNull(propertyEvent.getPreviousValues());

    }

    @Test
    public void shouldReceivePropertyAddedEventWhenRegisteredToReceiveEventsBasedUponNodeTypeName() throws Exception {
        // register listener
        String[] nodeTypeNames = {"mode:root"};
        SimpleListener listener = addListener(1, ALL_EVENTS, null, true, null, nodeTypeNames, false)
                .withExpectedNodePrimaryType("mode:root");

        // add the property
        this.session.getRootNode().setProperty("fooProp", new String[]{"foo", "bar"});
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        String propPath = "/fooProp";
        Event propertyAddedEvent = listener.getEvents().get(0);
        assertTrue("Path for added property is wrong: actual=" + propertyAddedEvent.getPath() + ", expected=" + propPath,
                   containsPath(listener, propPath));

        assertTrue(propertyAddedEvent instanceof PropertyEvent);
        PropertyEvent propertyEvent = (PropertyEvent) propertyAddedEvent;

        assertEquals("foo", propertyEvent.getCurrentValue());
        assertEquals(2, propertyEvent.getCurrentValues().size());
        assertEquals(Arrays.asList("foo", "bar"), propertyEvent.getCurrentValues());
        assertTrue(propertyEvent.isMultiValue());

        assertFalse(propertyEvent.wasMultiValue());
        assertNull(propertyEvent.getPreviousValue());
        assertNull(propertyEvent.getPreviousValues());
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.EventIteratorTest
    // ===========================================================================================================================

    /**
     * @see EventIteratorTest#testGetPosition()
     * @throws Exception
     */
    @Test
    public void shouldTestEventIteratorTest_testGetPosition() throws Exception {
        // register listener
        SimpleListener listener = addListener(3, Event.NODE_ADDED, null, false, null, null, false);

        // add nodes to generate events
        getRoot().addNode("node1", UNSTRUCTURED);
        getRoot().addNode("node2", UNSTRUCTURED);
        getRoot().addNode("node3", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
    }

    /**
     * @see EventIteratorTest#testGetSize()
     * @throws Exception
     */
    @Test
    public void shouldTestEventIteratorTest_testGetSize() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add node to generate event
        getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
    }

    @Test
    public void shouldTestEventIteratorTest_testSkip() throws Exception {
        // create events
        List<Event> events = new ArrayList<Event>();
        DateTime now = session.dateFactory().create();
        JcrEventBundle bundle = new JcrEventBundle(now, "userId", null);
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String id3 = UUID.randomUUID().toString();
        events.add(new JcrObservationManager.JcrEvent(bundle, Event.NODE_ADDED, "/testroot/node1",
                                                                                 id1, nodeType(JcrNtLexicon.UNSTRUCTURED), null));
        events.add(new JcrObservationManager.JcrEvent(bundle, Event.NODE_ADDED, "/testroot/node2", id2,
                                                                                 nodeType(JcrNtLexicon.UNSTRUCTURED), null));
        events.add(new JcrObservationManager.JcrEvent(bundle, Event.NODE_ADDED, "/testroot/node3", id3,
                                                                                 nodeType(JcrNtLexicon.UNSTRUCTURED), null));

        // create iterator
        EventIterator itr = new JcrObservationManager.JcrEventIterator(events);

        // tests
        itr.skip(0); // skip zero elements
        assertThat("getPosition() for first element should return 0.", itr.getPosition(), is(0L));

        itr.skip(2); // skip one element
        assertThat("Wrong value when skipping ", itr.getPosition(), is(2L));

        try {
            itr.skip(2); // skip past end
            fail("EventIterator must throw NoSuchElementException when skipping past the end");
        } catch (NoSuchElementException e) {
            // success
        }
    }


    private NodeType nodeType(Name name) {
        return session.repository().nodeTypeManager().getNodeTypes().getNodeType(name);
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.EventTest
    // ===========================================================================================================================

    /**
     * @see EventTest#testGetNodePath()
     * @throws Exception
     */
    @Test
    public void shouldTestEventTest_testGetNodePath() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add node to generate event
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));
    }

    /**
     * @see EventTest#testGetType()
     * @throws Exception
     */
    @Test
    public void shouldTestEventTest_testGetType() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add node to generate event
        getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertThat("Event did not return correct event type", listener.getEvents().get(0).getType(), is(Event.NODE_ADDED));
    }

    /**
     * @see EventTest#testGetUserId()
     * @throws Exception
     */
    @Test
    public void shouldTestEventTest_testGetUserId() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add a node to generate event
        getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertThat("UserId of event is not equal to userId of session", listener.getEvents().get(0).getUserID(), is(USER_ID));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.GetRegisteredEventListenersTest
    // ===========================================================================================================================

    /**
     * @see GetRegisteredEventListenersTest#testGetSize()
     * @throws Exception
     */
    @Test
    public void shouldTestGetRegisteredEventListenersTest_testGetSize() throws Exception {
        assertThat("A new session must not have any event listeners registered.",
                   getObservationManager().getRegisteredEventListeners().getSize(),
                   is(0L));

        // register listener
        SimpleListener listener = addListener(0, ALL_EVENTS, null, false, null, null, false);
        addListener(0, ALL_EVENTS, null, false, null, null, false);
        assertThat("Wrong number of event listeners.", getObservationManager().getRegisteredEventListeners().getSize(), is(2L));

        // make sure same listener isn't added again
        getObservationManager().addEventListener(listener, ALL_EVENTS, null, false, null, null, false);
        assertThat("The same listener should not be added more than once.", getObservationManager().getRegisteredEventListeners()
                                                                                                   .getSize(), is(2L));
    }

    /**
     * @see GetRegisteredEventListenersTest#testRemoveEventListener()
     * @throws Exception
     */
    @Test
    public void shouldTestGetRegisteredEventListenersTest_testRemoveEventListener() throws Exception {
        SimpleListener listener1 = addListener(0, ALL_EVENTS, null, false, null, null, false);
        EventListener listener2 = addListener(0, ALL_EVENTS, null, false, null, null, false);
        assertThat("Wrong number of event listeners.", getObservationManager().getRegisteredEventListeners().getSize(), is(2L));

        // now remove
        removeListener(listener1);
        assertThat("Wrong number of event listeners after removing a listener.",
                   getObservationManager().getRegisteredEventListeners().getSize(),
                   is(1L));
        assertThat("Wrong number of event listeners after removing a listener.",
                   getObservationManager().getRegisteredEventListeners().nextEventListener(),
                   is(listener2));

    }

    @Test
    @FixFor( "MODE-1407" )
    public void shouldTestLockingTest_testAddLockToNode() throws Exception {
        // setup
        String node1 = "node1";
        Node lockable = getRoot().addNode(node1, UNSTRUCTURED);
        lockable.addMixin(LOCK_MIXIN);
        save();

        // register listener
        SimpleListener listener = addListener(2, 2, Event.PROPERTY_ADDED, getRoot().getPath(), true, null, null, false);

        // lock node (no save needed)
        session.getWorkspace().getLockManager().lock(lockable.getPath(), false, true, 1L, "me");

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("No event created for " + LOCK_OWNER, containsPath(listener, lockable.getPath() + '/' + LOCK_OWNER));
        assertTrue("No event created for " + LOCK_IS_DEEP, containsPath(listener, lockable.getPath() + '/' + LOCK_IS_DEEP));
    }

    @Test
    @FixFor( "MODE-1407" )
    public void shouldTestLockingTest_testRemoveLockFromNode() throws Exception {
        // setup
        String node1 = "node1";
        Node lockable = getRoot().addNode(node1, UNSTRUCTURED);
        lockable.addMixin(LOCK_MIXIN);
        save();
        session.getWorkspace().getLockManager().lock(lockable.getPath(), false, true, 1L, "me");

        // register listener
        SimpleListener listener = addListener(2, Event.PROPERTY_REMOVED, null, false, null, null, false);

        // lock node (no save needed)
        session.getWorkspace().getLockManager().unlock(lockable.getPath());

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("No event created for " + LOCK_OWNER, containsPath(listener, lockable.getPath() + '/' + LOCK_OWNER));
        assertTrue("No event created for " + LOCK_IS_DEEP, containsPath(listener, lockable.getPath() + '/' + LOCK_IS_DEEP));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.NodeAddedTest
    // ===========================================================================================================================

    /**
     * @see NodeAddedTest#testMultipleNodeAdded1()
     * @throws Exception
     */
    @Test
    public void shouldTestNodeAddedTest_testMultipleNodeAdded1() throws Exception {
        // register listener
        SimpleListener listener = addListener(2, Event.NODE_ADDED, null, false, null, null, false);

        // add a couple sibling nodes
        Node addedNode1 = getRoot().addNode("node1", UNSTRUCTURED);
        Node addedNode2 = getRoot().addNode("node2", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for first added node is wrong", containsPath(listener, addedNode1.getPath()));
        assertTrue("Path for second added node is wrong", containsPath(listener, addedNode2.getPath()));
    }

    /**
     * @see NodeAddedTest#testMultipleNodeAdded2()
     * @throws Exception
     */
    @Test
    public void shouldTestNodeAddedTest_testMultipleNodeAdded2() throws Exception {
        // register listener
        SimpleListener listener = addListener(2, Event.NODE_ADDED, null, false, null, null, false);

        // add node and child node
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        Node addedChildNode = addedNode.addNode("node2", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong", containsPath(listener, addedNode.getPath()));
        assertTrue("Path for added child node is wrong", containsPath(listener, addedChildNode.getPath()));
    }

    /**
     * @see NodeAddedTest#testSingleNodeAdded()
     * @throws Exception
     */
    @Test
    public void shouldTestNodeAddedTest_testSingleNodeAdded() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add node
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));
    }

    /**
     * @see NodeAddedTest#testTransientNodeAddedRemoved()
     * @throws Exception
     */
    @Test
    public void shouldTestNodeAddedTest_testTransientNodeAddedRemoved() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add a child node and immediately remove it
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        Node transientNode = addedNode.addNode("node2", UNSTRUCTURED); // should not get this event because of the following
        // remove
        transientNode.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.NodeRemovedTest
    // ===========================================================================================================================

    /**
     * @see NodeRemovedTest#testMultiNodesRemoved()
     * @throws Exception
     */
    @Test
    public void shouldTestNodeRemovedTest_testMultiNodesRemoved() throws Exception {
        // register listener
        SimpleListener listener = addListener(2, Event.NODE_REMOVED, null, false, null, null, false);

        // add nodes to be removed
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        Node childNode = addedNode.addNode("node2", UNSTRUCTURED);
        save();

        // remove parent node which removes child node
        String parentPath = addedNode.getPath();
        String childPath = childNode.getPath();
        addedNode.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for removed node is wrong", containsPath(listener, parentPath));
        assertTrue("Path for removed child node is wrong", containsPath(listener, childPath));
    }

    /**
     * @see NodeRemovedTest#testSingleNodeRemoved()
     * @throws Exception
     */
    @Test
    public void shouldTestNodeRemovedTest_testSingleNodeRemoved() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // add the node that will be removed
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // remove node
        String path = addedNode.getPath();
        addedNode.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for removed node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected=" + path,
                   containsPath(listener, path));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.NodeMovedTest
    // ===========================================================================================================================

    /**
     * @see NodeMovedTest#testMoveNode()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldTestNodeMovedTest_testMoveNode() throws Exception {
        // setup
        String node1 = "node1";
        String node2 = "node2";
        Node n1 = getRoot().addNode(node1, UNSTRUCTURED);
        Node n2 = n1.addNode(node2, UNSTRUCTURED);
        String oldPath = n2.getPath();
        save();

        // register listeners
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // move node
        String newPath = getRoot().getPath() + '/' + node2;
        getWorkspace().move(oldPath, newPath);
        save();

        // event handling
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(oldPath));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(newPath));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(nullValue()));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + newPath, containsPath(moveNodeListener, newPath));
        assertTrue("Path for new location of added node is wrong: actual=" + addNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + newPath, containsPath(addNodeListener, newPath));
        assertTrue("Path for new location of removed node is wrong: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + oldPath, containsPath(removeNodeListener, oldPath));
    }

    /**
     * @see NodeMovedTest#testMoveTree()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    @FixFor( "MODE-1410" )
    public void shouldTestNodeMovedTest_testMoveTree() throws Exception {
        // setup
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        String oldPath = n1.getPath();
        n1.addNode("node2", UNSTRUCTURED);
        save();

        // register listeners
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // move node
        String newPath = getRoot().getPath() + "/node3";
        getWorkspace().move(oldPath, newPath);
        save();

        // event handling
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(oldPath));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(newPath));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(nullValue()));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + newPath, containsPath(moveNodeListener, newPath));
        assertTrue("Path for new location of added node is wrong: actual=" + addNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + newPath, containsPath(addNodeListener, newPath));
        assertTrue("Path for new location of removed node is wrong: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + oldPath, containsPath(removeNodeListener, oldPath));
    }

    /**
     * @see NodeMovedTest#testMoveWithRemove()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldTestNodeMovedTest_testMoveWithRemove() throws Exception {
        // setup
        String node2 = "node2";
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        Node n2 = n1.addNode(node2, UNSTRUCTURED);
        Node n3 = getRoot().addNode("node3", UNSTRUCTURED);
        save();

        // register listeners
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(2, 2, Event.NODE_REMOVED, null, false, null, null, false);

        // move node
        String oldPath = n2.getPath();
        String newPath = n3.getPath() + '/' + node2;
        getWorkspace().move(oldPath, newPath);

        // remove node
        String removedNodePath = n1.getPath();
        n1.remove();
        save();

        // event handling
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(oldPath));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(newPath));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + newPath, containsPath(moveNodeListener, newPath));
        assertTrue("Path for new location of added node is wrong: actual=" + addNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + newPath, containsPath(addNodeListener, newPath));
        assertTrue("Path for new location of removed node is wrong: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + oldPath, containsPath(removeNodeListener, oldPath));
        assertTrue("Path for removed node is wrong", containsPath(removeNodeListener, removedNodePath));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.NodeReorderTest
    // ===========================================================================================================================

    /**
     * @see NodeReorderTest#testNodeReorderSameName()
     * @see NodeReorderTest#testNodeReorderSameNameWithRemove()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldTestNodeReorderTest_testNodeReorder() throws Exception {
        // setup
        getRoot().addNode("node1", UNSTRUCTURED);
        Node n2 = getRoot().addNode("node2", UNSTRUCTURED);
        Node n3 = getRoot().addNode("node3", UNSTRUCTURED);
        save();

        // register listeners
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // reorder to trigger events
        getRoot().orderBefore(n3.getName(), n2.getName());
        save();

        // handle events
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is("node3"));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is("node2"));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + n3.getPath(), containsPath(moveNodeListener, n3.getPath()));
        assertTrue("Added reordered node has wrong path: actual=" + addNodeListener.getEvents().get(0).getPath() + ", expected="
                   + n3.getPath(), containsPath(addNodeListener, n3.getPath()));
        assertTrue("Removed reordered node has wrong path: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + n3.getPath(), containsPath(removeNodeListener, n3.getPath()));
    }

    /**
     * @see NodeReorderTest#testNodeReorderSameName()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    @FixFor( "MODE-1409" )
    public void shouldTestNodeReorderTest_testNodeReorderSameName() throws Exception {
        // setup
        String node1 = "node1";
        Node n1 = getRoot().addNode(node1, UNSTRUCTURED);
        getRoot().addNode(node1, UNSTRUCTURED);
        getRoot().addNode(node1, UNSTRUCTURED);
        save();

        // register listeners + "[2]"
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // reorder to trigger events
        getRoot().orderBefore(node1 + "[3]", node1 + "[2]");
        save();

        // handle events
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(node1 + "[3]"));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(node1 + "[2]"));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + getRoot().getPath() + "/" + node1 + "[2]",
                   containsPath(moveNodeListener, getRoot().getPath() + "/" + node1 + "[2]"));
        assertTrue("Added reordered node has wrong path: actual=" + addNodeListener.getEvents().get(0).getPath() + ", expected="
                   + n1.getPath() + "[2]", containsPath(addNodeListener, n1.getPath() + "[2]"));
        assertTrue("Removed reordered node has wrong path: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + n1.getPath() + "[3]", containsPath(removeNodeListener, n1.getPath() + "[3]"));
    }

    /**
     * @see NodeReorderTest#testNodeReorderSameNameWithRemove()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    @FixFor( "MODE-1409" )
    public void shouldTestNodeReorderTest_testNodeReorderSameNameWithRemove() throws Exception {
        // setup
        String node1 = "node1";
        Node n1 = getRoot().addNode(node1, UNSTRUCTURED);
        getRoot().addNode("node2", UNSTRUCTURED);
        getRoot().addNode(node1, UNSTRUCTURED);
        getRoot().addNode(node1, UNSTRUCTURED);
        Node n3 = getRoot().addNode("node3", UNSTRUCTURED);
        save();

        // register listeners + "[2]"
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(2, Event.NODE_REMOVED, null, false, null, null, false);

        // trigger events
        getRoot().orderBefore(node1 + "[2]", null);
        String removedPath = n3.getPath();
        n3.remove();
        save();

        // handle events
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        List<Event> moveNodeListenerEvents = moveNodeListener.getEvents();
        assertFalse(moveNodeListenerEvents.isEmpty());
        Map<String, String> info = moveNodeListenerEvents.get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(node1 + "[2]"));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(nullValue()));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListenerEvents.get(0).getPath()
                   + ", expected=" + getRoot().getPath() + "/" + node1 + "[3]",
                   containsPath(moveNodeListener, getRoot().getPath() + "/" + node1 + "[3]"));
        assertTrue("Added reordered node has wrong path: actual=" + addNodeListener.getEvents().get(0).getPath() + ", expected="
                   + n1.getPath() + "[3]", containsPath(addNodeListener, n1.getPath() + "[3]"));
        assertTrue("Removed reordered node path not found: " + n1.getPath() + "[2]",
                   containsPath(removeNodeListener, n1.getPath() + "[2]"));
        assertTrue("Removed node path not found: " + removedPath, containsPath(removeNodeListener, removedPath));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.PropertyAddedTest
    // ===========================================================================================================================

    /**
     * @see PropertyAddedTest#testMultiPropertyAdded()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyAddedTest_testMultiPropertyAdded() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // register listener
        SimpleListener listener = addListener(2, Event.PROPERTY_ADDED, null, false, null, null, false);

        // add multiple properties
        Property prop1 = node.setProperty("prop1", "prop1 content");
        Property prop2 = node.setProperty("prop2", "prop2 content");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for first added property not found: " + prop1.getPath(), containsPath(listener, prop1.getPath()));
        assertTrue("Path for second added property not found: " + prop2.getPath(), containsPath(listener, prop2.getPath()));
    }

    /**
     * @see PropertyAddedTest#testSinglePropertyAdded()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyAddedTest_testSinglePropertyAdded() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // register listener
        SimpleListener listener = addListener(1, Event.PROPERTY_ADDED, null, false, null, null, false);

        // add the property
        Property prop1 = node.setProperty("prop1", "prop1 content");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added property is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + prop1.getPath(),
                   containsPath(listener, prop1.getPath()));
    }

    /**
     * @see PropertyAddedTest#testSystemGenerated()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyAddedTest_testSystemGenerated() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.PROPERTY_ADDED, null, false, null, null, false);

        // create node (which adds 1 property)
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for jrc:primaryType property was not found.",
                   containsPath(listener, node.getProperty("jcr:primaryType").getPath()));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.PropertyChangedTests
    // ===========================================================================================================================

    /**
     * @see PropertyChangedTest#testMultiPropertyChanged()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyChangedTests_testMultiPropertyChanged() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop1 = node.setProperty("prop1", "prop1 content");
        Property prop2 = node.setProperty("prop2", "prop2 content");
        save();

        // register listener
        SimpleListener listener = addListener(2, Event.PROPERTY_CHANGED, null, false, null, null, false);

        // add multiple properties
        prop1.setValue("prop1 modified content");
        prop2.setValue("prop2 modified content");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for first changed property not found: " + prop1.getPath(), containsPath(listener, prop1.getPath()));
        assertTrue("Path for second changed property not found: " + prop2.getPath(), containsPath(listener, prop2.getPath()));
    }

    /**
     * @see PropertyChangedTest#testPropertyRemoveCreate()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyChangedTests_testPropertyRemoveCreate() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        String propName = "prop1";
        Property prop = node.setProperty(propName, propName + " content");
        String propPath = prop.getPath();
        save();

        // register listeners
        SimpleListener listener1 = addListener(1, Event.PROPERTY_CHANGED, null, false, null, null, false);
        SimpleListener listener2 = addListener(2, Event.PROPERTY_ADDED | Event.PROPERTY_REMOVED, null, false, null, null, false);

        // trigger events
        prop.remove();
        node.setProperty(propName, true);
        save();

        // event handling
        listener1.waitForEvents();
        removeListener(listener1);
        listener2.waitForEvents();
        removeListener(listener2);

        // tests
        if (listener1.getEvents().size() == 1) {
            checkResults(listener1);
            assertTrue("Path for removed then added property is wrong: actual=" + listener1.getEvents().get(0).getPath()
                       + ", expected=" + propPath, containsPath(listener1, propPath));
        } else {
            checkResults(listener2);
            assertTrue("Path for removed then added property is wrong: actual=" + listener2.getEvents().get(0).getPath()
                       + ", expected=" + propPath, containsPath(listener2, propPath));
            assertTrue("Path for removed then added property is wrong: actual=" + listener2.getEvents().get(1).getPath()
                       + ", expected=" + propPath, containsPath(listener2, propPath));
        }
    }

    /**
     * @see PropertyChangedTest#testSinglePropertyChanged()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyChangedTests_testSinglePropertyChanged() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop1 = node.setProperty("prop1", "prop1 content");
        save();

        // register listener
        SimpleListener listener = addListener(1, Event.PROPERTY_CHANGED, null, false, null, null, false);

        // change the property
        prop1.setValue("prop1 modified content");
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for changed property is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + prop1.getPath(),
                   containsPath(listener, prop1.getPath()));
    }

    /**
     * @see PropertyChangedTest#testSinglePropertyChangedWithAdded()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyChangedTests_testSinglePropertyChangedWithAdded() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop1 = node.setProperty("prop1", "prop1 content");
        save();

        // register listener
        SimpleListener listener = addListener(1, Event.PROPERTY_CHANGED, null, false, null, null, false);

        // change the property
        prop1.setValue("prop1 modified content");
        node.setProperty("prop2", "prop2 content"); // property added event should not be received
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for changed property is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + prop1.getPath(),
                   containsPath(listener, prop1.getPath()));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.PropertyRemovedTest
    // ===========================================================================================================================

    /**
     * @see PropertyRemovedTest#testMultiPropertyRemoved()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyRemovedTest_testMultiPropertyRemoved() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop1 = node.setProperty("prop1", "prop1 content");

        Property prop2 = node.setProperty("prop2", "prop2 content");
        save();

        // register listener
        SimpleListener listener = addListener(2, Event.PROPERTY_REMOVED, null, false, null, null, false);

        // remove the property
        String prop1Path = prop1.getPath();
        prop1.remove();
        String prop2Path = prop2.getPath();
        prop2.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for first removed property not found: " + prop1Path, containsPath(listener, prop1Path));
        assertTrue("Path for second removed property not found: " + prop2Path, containsPath(listener, prop2Path));
    }

    /**
     * @see PropertyRemovedTest#testSinglePropertyRemoved()
     * @throws Exception
     */
    @Test
    public void shouldTestPropertyRemovedTest_testSinglePropertyRemoved() throws Exception {
        // setup
        Node node = getRoot().addNode("node1", UNSTRUCTURED);
        Property prop = node.setProperty("prop1", "prop1 content");
        String propPath = prop.getPath();
        save();

        // register listener
        SimpleListener listener = addListener(1, Event.PROPERTY_REMOVED, null, false, null, null, false);

        // remove the property
        prop.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for removed property is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + propPath, containsPath(listener, propPath));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.AddEventListenerTest
    // ===========================================================================================================================

    /**
     * @see AddEventListenerTest#testIsDeepFalseNodeAdded()
     * @throws Exception
     */
    @Test
    public void shouldTestAddEventListenerTest_testIsDeepFalseNodeAdded() throws Exception {
        // setup
        String node1 = "node1";
        String path = getRoot().getPath() + '/' + node1;

        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, path, false, null, null, false);

        // add child node under the path we care about
        Node n1 = getRoot().addNode(node1, UNSTRUCTURED);
        Node childNode = n1.addNode("node2", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        checkResults(listener);
        assertTrue("Child node path is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + childNode.getPath(),
                   containsPath(listener, childNode.getPath()));
    }

    /**
     * @see AddEventListenerTest#testIsDeepFalsePropertyAdded()
     * @throws Exception
     */
    @Test
    public void shouldTestAddEventListenerTest_testIsDeepFalsePropertyAdded() throws Exception {
        // setup
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        Node n2 = getRoot().addNode("node2", UNSTRUCTURED);
        save();

        // register listener
        SimpleListener listener = addListener(1, Event.PROPERTY_ADDED, n1.getPath(), false, null, null, false);

        // add property
        String prop = "prop";
        Property n1Prop = n1.setProperty(prop, "foo");
        n2.setProperty(prop, "foo"); // should not receive event for this
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added property is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + n1Prop.getPath(),
                   containsPath(listener, n1Prop.getPath()));
    }

    /**
     * @see AddEventListenerTest#testNodeType()
     * @throws Exception
     */
    @Test
    public void shouldTestAddEventListenerTest_testNodeType() throws Exception {
        // setup
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        n1.addMixin(LOCK_MIXIN);
        Node n2 = getRoot().addNode("node2", UNSTRUCTURED);
        save();

        // register listener
        SimpleListener listener = addListener(1,
                                              Event.NODE_ADDED,
                                              getRoot().getPath(),
                                              true,
                                              null,
                                              new String[] {LOCK_MIXIN},
                                              false);

        // trigger events
        String node3 = "node3";
        Node n3 = n1.addNode(node3, UNSTRUCTURED);
        n2.addNode(node3, UNSTRUCTURED);
        save();

        // handle events
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Wrong path: actual=" + listener.getEvents().get(0).getPath() + ", expected=" + n3.getPath(),
                   containsPath(listener, n3.getPath()));
    }

    /**
     * @see AddEventListenerTest#testNoLocalTrue()
     * @throws Exception
     */
    @Test
    public void shouldTestAddEventListenerTest_testNoLocalTrue() throws Exception {
        // register listener
        SimpleListener listener = addListener(0, Event.NODE_ADDED, getRoot().getPath(), true, null, null, true);

        // trigger events
        getRoot().addNode("node1", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
    }

    /**
     * @see AddEventListenerTest#testPath()
     * @throws Exception
     */
    @Test
    public void shouldTestAddEventListenerTest_testPath() throws Exception {
        // setup
        String node1 = "node1";
        String path = getRoot().getPath() + '/' + node1;

        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, path, true, null, null, false);

        // add child node under the path we care about
        Node n1 = getRoot().addNode(node1, UNSTRUCTURED);
        Node childNode = n1.addNode("node2", UNSTRUCTURED);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Child node path is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + childNode.getPath(),
                   containsPath(listener, childNode.getPath()));
    }

    /**
     * @see AddEventListenerTest#testUUID()
     * @throws Exception
     */
    @Test
    public void shouldTestAddEventListenerTest_testUUID() throws Exception {
        // setup
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        n1.addMixin(REF_MIXIN);
        Node n2 = getRoot().addNode("node2", UNSTRUCTURED);
        n2.addMixin(REF_MIXIN);
        save();

        // register listener
        SimpleListener listener = addListener(1,
                                              Event.PROPERTY_ADDED,
                                              getRoot().getPath(),
                                              true,
                                              new String[] {n1.getIdentifier()},
                                              null,
                                              false);

        // create properties
        String prop1 = "prop1";
        Property n1Prop = n1.setProperty(prop1, "foo");
        n2.setProperty(prop1, "foo"); // should not get an event for this
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Wrong path: actual=" + listener.getEvents().get(0).getPath() + ", expected=" + n1Prop.getPath(),
                   containsPath(listener, n1Prop.getPath()));
    }

    // ===========================================================================================================================
    // @see org.apache.jackrabbit.test.api.observation.WorkspaceOperationTest
    // ===========================================================================================================================

    /**
     * @see WorkspaceOperationTest#testCopy()
     * @throws Exception
     */
    @FixFor( "MODE-1312" )
    @Test
    public void shouldTestWorkspaceOperationTest_testCopy() throws Exception {
        // setup
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);
        String node2 = "node2";
        addedNode.addNode(node2, UNSTRUCTURED);
        save();

        // register listener
        SimpleListener listener = addListener(2, Event.NODE_ADDED, null, false, null, null, false);

        // perform copy
        String targetPath = getRoot().getPath() + "/node3";
        getWorkspace().copy(addedNode.getPath(), targetPath);

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for copied node not found: " + targetPath, containsPath(listener, targetPath));
        assertTrue("Path for copied child node not found: " + (targetPath + '/' + node2),
                   containsPath(listener, (targetPath + '/' + node2)));
    }

    /**
     * @see WorkspaceOperationTest#testMove()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldTestWorkspaceOperationTest_testMove() throws Exception {
        // setup
        String node2 = "node2";
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        n1.addNode(node2, UNSTRUCTURED);
        Node n3 = getRoot().addNode("node3", UNSTRUCTURED);
        save();

        // register listeners
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // perform move
        String oldPath = n1.getPath();
        String targetPath = n3.getPath() + "/node4";
        getWorkspace().move(oldPath, targetPath);
        save();

        // event handling
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(oldPath));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(targetPath));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(nullValue()));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + targetPath, containsPath(moveNodeListener, targetPath));
        assertTrue("Path for new location of moved node is wrong: actual=" + addNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + targetPath, containsPath(addNodeListener, targetPath));
        assertTrue("Path for old location of moved node is wrong: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + oldPath, containsPath(removeNodeListener, oldPath));
    }

    /**
     * @see WorkspaceOperationTest#testRename()
     * @throws Exception
     */
    @SuppressWarnings( "unchecked" )
    @Test
    @FixFor( "MODE-1410" )
    public void shouldTestWorkspaceOperationTest_testRename() throws Exception {
        // setup
        Node n1 = getRoot().addNode("node1", UNSTRUCTURED);
        n1.addNode("node2", UNSTRUCTURED);
        save();

        // register listeners
        SimpleListener moveNodeListener = addListener(1, Event.NODE_MOVED, null, false, null, null, false);
        SimpleListener addNodeListener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);
        SimpleListener removeNodeListener = addListener(1, Event.NODE_REMOVED, null, false, null, null, false);

        // rename node
        String oldPath = n1.getPath();
        String renamedPath = getRoot().getPath() + "/node3";
        getWorkspace().move(oldPath, renamedPath);

        // event handling
        moveNodeListener.waitForEvents();
        removeListener(moveNodeListener);
        addNodeListener.waitForEvents();
        removeListener(addNodeListener);
        removeNodeListener.waitForEvents();
        removeListener(removeNodeListener);

        // tests
        checkResults(moveNodeListener);
        checkResults(addNodeListener);
        checkResults(removeNodeListener);
        Map<String, String> info = moveNodeListener.getEvents().get(0).getInfo();
        assertThat(info.get(JcrObservationManager.MOVE_FROM_KEY), is(oldPath));
        assertThat(info.get(JcrObservationManager.MOVE_TO_KEY), is(renamedPath));
        assertThat(info.get(JcrObservationManager.ORDER_SRC_KEY), is(nullValue()));
        assertThat(info.get(JcrObservationManager.ORDER_DEST_KEY), is(nullValue()));
        assertTrue("Path for new location of moved node is wrong: actual=" + moveNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + renamedPath, containsPath(moveNodeListener, renamedPath));
        assertTrue("Path for renamed node is wrong: actual=" + addNodeListener.getEvents().get(0).getPath() + ", expected="
                   + renamedPath, containsPath(addNodeListener, renamedPath));
        assertTrue("Path for old name of renamed node is wrong: actual=" + removeNodeListener.getEvents().get(0).getPath()
                   + ", expected=" + oldPath, containsPath(removeNodeListener, oldPath));
    }

    @Test
    public void shouldNotReceiveEventsFromOtherWorkspaces() throws Exception {
        // Log into a second workspace ...
        Session session2 = login(WORKSPACE2);

        // Register 2 listeners in the first session ...
        SimpleListener listener1 = addListener(session, 2, ALL_EVENTS, "/", true, null, null, false);
        SimpleListener addListener1 = addListener(session, 1, Event.NODE_ADDED, "/", true, null, null, false);

        // Register 2 listeners in the second session ...
        SimpleListener listener2 = addListener(session2, 0, ALL_EVENTS, "/", true, null, null, false);
        SimpleListener addListener2 = addListener(session2, 0, Event.NODE_ADDED, "/", true, null, null, false);

        // Add a node to the first session ...
        session.getRootNode().addNode("nodeA", "nt:unstructured");
        session.save();

        // Wait for the events on the first session's listeners (that should get the events) ...
        listener1.waitForEvents();
        addListener1.waitForEvents();
        removeListener(listener1);
        removeListener(addListener1);

        // Wait for the events on the second session's listeners (that should NOT get the events) ...
        listener2.waitForEvents();
        // addListener2.waitForEvents();
        removeListener(listener2);
        removeListener(addListener2);

        // Verify the expected events were received ...
        checkResults(listener1);
        checkResults(addListener1);
        checkResults(listener2);
        checkResults(addListener2);
    }

    @FixFor( "MODE-786" )
    @Test
    public void shouldReceiveEventsForChangesToSessionNamespacesInSystemContent() throws Exception {
        String uri = "http://acme.com/example/foobar/";
        String prefix = "foobar";
        assertNoSessionNamespace(uri, prefix);

        SimpleListener listener = addListener(session, 0, ALL_EVENTS, "/jcr:system", true, null, null, false);
        session.setNamespacePrefix(prefix, uri);

        // Wait for the events on the session's listeners (that should NOT get the events) ...
        listener.waitForEvents();
        removeListener(listener);

        // Verify the expected events were received ...
        checkResults(listener);
    }

    @FixFor( "MODE-1408" )
    @Test
    public void shouldReceiveEventsForChangesToRepositoryNamespacesInSystemContent() throws Exception {
        String uri = "http://acme.com/example/foobar/";
        String prefix = "foobar";
        assertNoRepositoryNamespace(uri, prefix);

        Session session2 = login(WORKSPACE2);

        SimpleListener listener = addListener(session, 4, ALL_EVENTS, "/jcr:system", true, null, null, false);
        SimpleListener listener2 = addListener(session2, 4, ALL_EVENTS, "/jcr:system", true, null, null, false);

        session.getWorkspace().getNamespaceRegistry().registerNamespace(prefix, uri);

        // Wait for the events on the session's listeners (that should get the events) ...
        listener.waitForEvents();
        listener2.waitForEvents();
        removeListener(listener);
        removeListener(listener2);

        assertThat(session.getWorkspace().getNamespaceRegistry().getPrefix(uri), is(prefix));
        assertThat(session.getWorkspace().getNamespaceRegistry().getURI(prefix), is(uri));

        // Verify the expected events were received ...
        checkResults(listener);
        checkResults(listener2);
    }

    @Test
    public void shouldReceiveEventsForChangesToLocksInSystemContent() throws Exception {
        Node root = session.getRootNode();
        Node parentNode = root.addNode("lockedPropParent");
        parentNode.addMixin("mix:lockable");

        Node targetNode = parentNode.addNode("lockedTarget");
        targetNode.setProperty("foo", "bar");
        session.save();

        // no event should be fired from the system path for the lock (excluded explicitly because the TCK does not expect events
        // from this)
        SimpleListener systemListener = addListener(session, 1, 2, ALL_EVENTS, "/jcr:system", true, null, null, false);
        // 2 events (property added for isDeep and lock owner) should be fired for the lock in the regular path (as per TCK)
        SimpleListener nodeListener = addListener(session, 2, 2, ALL_EVENTS, parentNode.getPath(), true, null, null, false);

        lock(parentNode, true, true);

        // Wait for the events on the session's listeners (that should get the events) ...
        systemListener.waitForEvents();
        removeListener(systemListener);
        // Verify the expected events were received ...
        checkResults(systemListener);

        nodeListener.waitForEvents();
        removeListener(nodeListener);
        checkResults(nodeListener);
    }

    @FixFor( "MODE-786" )
    @Test
    public void shouldReceiveEventsForChangesToVersionsInSystemContent() throws Exception {
        int numEvents = 22;
        SimpleListener listener = addListener(session, numEvents, ALL_EVENTS, "/jcr:system", true, null, null, false);

        Node node = session.getRootNode().addNode("/test", "nt:unstructured");
        node.addMixin("mix:versionable");
        session.save(); // SHOULD GENERATE AN EVENT TO CREATE VERSION HISTORY FOR THE NODE

        // Wait for the events on the session's listeners (that should get the events) ...
        listener.waitForEvents();
        removeListener(listener);

        Node history = node.getProperty("jcr:versionHistory").getNode();
        assertThat(history, is(notNullValue()));

        assertThat(node.hasProperty("jcr:baseVersion"), is(true));
        Node version = node.getProperty("jcr:baseVersion").getNode();
        assertThat(version, is(notNullValue()));

        assertThat(version.getParent(), is(history));

        assertThat(node.hasProperty("jcr:uuid"), is(true));
        assertThat(node.getProperty("jcr:uuid").getString(), is(history.getProperty("jcr:versionableUuid").getString()));

        assertThat(versionHistory(node).getIdentifier(), is(history.getIdentifier()));
        assertThat(versionHistory(node).getPath(), is(history.getPath()));

        assertThat(baseVersion(node).getIdentifier(), is(version.getIdentifier()));
        assertThat(baseVersion(node).getPath(), is(version.getPath()));

        // Verify the expected events were received ...
        checkResults(listener);
    }

    @FixFor( " MODE-1315 " )
    @Test
    public void shouldReceiveEventWhenPropertyDeletedOnCustomNode() throws Exception {
        session.getWorkspace()
               .getNodeTypeManager()
               .registerNodeTypes(getClass().getClassLoader().getResourceAsStream("cars.cnd"), true);

        Node car = getRoot().addNode("car", "car:Car");
        car.setProperty("car:maker", "Audi");
        session.save();

        SimpleListener listener = addListener(1, Event.PROPERTY_REMOVED, null, true, null, new String[] {"car:Car"}, false);
        Property carMakerProperty = car.getProperty("car:maker");
        String propertyPath = carMakerProperty.getPath();
        carMakerProperty.remove();
        session.save();
        listener.waitForEvents();
        checkResults(listener);
        Event receivedEvent = listener.getEvents().get(0);
        assertEquals(Event.PROPERTY_REMOVED, receivedEvent.getType());
        assertEquals(propertyPath, receivedEvent.getPath());
    }

    @FixFor( "MODE-1370" )
    @Test
    public void shouldReceiveUserDataWithEventWhenObservationSessionIsSameThatMadeChange() throws Exception {
        // register listener
        SimpleListener listener = addListener(1, Event.NODE_ADDED, null, false, null, null, false);

        // add node
        Node addedNode = getRoot().addNode("node1", UNSTRUCTURED);

        // Add user-data to the observation manager ...
        String userData = "my user data";
        getRoot().getSession().getWorkspace().getObservationManager().setUserData(userData);
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));

        // Now check the userdata in the events ...
        for (Event event : listener.events) {
            String eventUserData = event.getUserData();
            assertThat(eventUserData, is(userData));
        }

        // Now check the userdata ...
        assertThat(listener.userData.size(), is(not(0)));
        for (String receivedUserData : listener.userData) {
            assertThat(receivedUserData, is(userData));
        }
    }

    @FixFor( "MODE-1370" )
    @Test
    public void shouldReceiveUserDataWithEventWhenUserDataSetOnSessionThatMadeChange() throws Exception {
        // Now register a listener
        SimpleListener listener = new SimpleListener(1, 1, Event.NODE_ADDED);
        session.getWorkspace()
               .getObservationManager()
               .addEventListener(listener, Event.NODE_ADDED, null, true, null, null, false);

        // Create a new session ...
        Session session2 = login(WORKSPACE);

        // Add user-data to the observation manager for our session ...
        String userData = "my user data";
        session2.getWorkspace().getObservationManager().setUserData(userData);
        // add node and save
        Node addedNode = session2.getRootNode().addNode("node1", UNSTRUCTURED);
        session2.save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));

        // Now check the userdata in the events ...
        for (Event event : listener.events) {
            String eventUserData = event.getUserData();
            assertThat(eventUserData, is(userData));
        }

        // Now check the userdata ...
        assertThat(listener.userData.size(), is(not(0)));
        for (String receivedUserData : listener.userData) {
            assertThat(receivedUserData, is(userData));
        }
    }

    @FixFor( "MODE-1370" )
    @Test
    public void shouldNotReceiveUserDataWithEventIfUserDataSetOnObservationSession() throws Exception {
        // Add user-data to the observation manager using the observation session ...
        String userData = "my user data";
        ObservationManager om = session.getWorkspace().getObservationManager();
        om.setUserData(userData);

        // Now register a listener
        SimpleListener listener = new SimpleListener(1, 1, Event.NODE_ADDED);
        session.getWorkspace()
               .getObservationManager()
               .addEventListener(listener, Event.NODE_ADDED, null, true, null, null, false);

        // Create a new session and do NOT set the user data ...
        Session session2 = login(WORKSPACE);

        // but add node and save ...
        Node addedNode = session2.getRootNode().addNode("node1", UNSTRUCTURED);
        session2.save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));

        // Now check the userdata in the events ...
        for (Event event : listener.events) {
            String eventUserData = event.getUserData();
            assertThat(eventUserData, is(nullValue()));
        }

        // Now check the userdata ...
        assertThat(listener.userData.size(), is(1));
        for (String receivedUserData : listener.userData) {
            assertThat(receivedUserData, is(nullValue()));
        }
    }

    @Test
    @FixFor( "MODE-2268" )
    public void shouldReceiveNonLocalEvents() throws Exception {
        // register a non-local listener with the 1st session
        SimpleListener listener = new SimpleListener(1, 1, Event.NODE_ADDED);
        session.getWorkspace()
               .getObservationManager()
               .addEventListener(listener, Event.NODE_ADDED, null, true, null, null, true);

        //create a node in the 1st session - the listener should not get this event
        session.getRootNode().addNode("session1Node");
        session.save();

        // Create a new session ...
        Session session2 = login(WORKSPACE);

        // add node and save
        Node addedNode = session2.getRootNode().addNode("session2Node");
        session2.save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for added node is wrong: actual=" + listener.getEvents().get(0).getPath() + ", expected="
                   + addedNode.getPath(),
                   containsPath(listener, addedNode.getPath()));
    }

    @Test
    @FixFor( "MODE-2019" )
    public void shouldProvideFullEventJournal() throws Exception {
        // add node
        Node node1 = getRoot().addNode("node1");
        node1.setProperty("prop1", "value");
        node1.setProperty("prop2", "value2");
        Node node2 = getRoot().addNode("node2");
        session.save();
        Thread.sleep(100);

        node1.setProperty("prop2", "edited value");
        node1.setProperty("prop1", (String) null);
        node2.remove();
        session.save();
        Thread.sleep(100);

        EventJournal eventJournal = getObservationManager().getEventJournal();
        assertPathsInJournal(eventJournal, false,
                             "/testroot/node1", "/testroot/node1/jcr:primaryType", "/testroot/node1/prop1",
                             "/testroot/node1/prop2", "/testroot/node2/jcr:primaryType", "/testroot/node2/jcr:primaryType",
                             "/testroot/node1/prop2", "/testroot/node1/prop1", "/testroot/node1/prop2");

    }

    @Test
    @FixFor( "MODE-2019" )
    public void shouldProvideFilteredEventJournal() throws Exception {
        // add node
        Node node1 = getRoot().addNode("node1");
        getRoot().addNode("node2");
        Node node3 = getRoot().addNode("node3");
        session.save();
        Thread.sleep(200);


        EventJournal eventJournal = getObservationManager().getEventJournal(org.modeshape.jcr.api.observation.Event.ALL_EVENTS,
                                                                            null, true, new String[]{node1.getIdentifier(),
                                                                                                     node3.getIdentifier()},
                                                                            null);
        assertPathsInJournal(eventJournal, true,
                             "/testroot/node1", "/testroot/node1/jcr:primaryType",
                             "/testroot/node3", "/testroot/node3/jcr:primaryType");
    }

    @Test
    @FixFor( "MODE-2019" )
    public void shouldSkipToEventsInJournal() throws Exception {
        long startingDate = System.currentTimeMillis();
        getRoot().addNode("node1");
        session.save();
        Thread.sleep(100);
        long afterNode1 = System.currentTimeMillis();

        getRoot().addNode("node2");
        session.save();
        Thread.sleep(100);
        long afterNode2 = System.currentTimeMillis();

        getRoot().addNode("node3");
        session.save();
        Thread.sleep(100);
        long afterNode3 = System.currentTimeMillis();

        EventJournal journal = getObservationManager().getEventJournal();
        journal.skipTo(startingDate);
        assertPathsInJournal(journal, true,
                             "/testroot/node1", "/testroot/node1/jcr:primaryType",
                             "/testroot/node2", "/testroot/node2/jcr:primaryType",
                             "/testroot/node3", "/testroot/node3/jcr:primaryType");

        journal = getObservationManager().getEventJournal();
        journal.skipTo(afterNode1);
        assertPathsInJournal(journal, true,
                             "/testroot/node2", "/testroot/node2/jcr:primaryType",
                             "/testroot/node3", "/testroot/node3/jcr:primaryType");

        journal = getObservationManager().getEventJournal();
        journal.skipTo(afterNode2);
        assertPathsInJournal(journal, true,
                             "/testroot/node3", "/testroot/node3/jcr:primaryType");

        journal = getObservationManager().getEventJournal();
        journal.skipTo(afterNode3);
        assertFalse(journal.hasNext());
    }

    @Test
    @FixFor( "MODE-2336" )
    public void shouldReceiveNodeTypeFilteredEventsWithUserTransactions() throws Exception {
        stopRepository();
        startRepositoryWithConfigurationFrom("config/repo-config-inmemory-txn.json");
        session = repository.login();
        // initialize workspace
        session.getRootNode().addNode("folder1");
        session.save();
        // register listener for PropertyEvent with nodeType restriction
        Session listenerSession = newSession();
        EventListener listener = events -> {
            while (events.hasNext()) {
                events.nextEvent();
            }
        };
        listenerSession
                .getWorkspace()
                .getObservationManager()
                .addEventListener(listener, ALL_EVENTS, "/folder1", true, null, new String[] { "nt:unstructured" }, false);

        // try to add nodes within transactions
        TransactionManager txMgr = repository.transactionManager();
        for (int i = 0; i < 100; i++) {
            txMgr.begin();
            Session writerSession = repository().login();
            String nodeName = "node" + i;
            writerSession.getNode("/folder1").addNode(nodeName);
            writerSession.save();
            writerSession.logout();
            txMgr.commit();
        }
        // wait for listener thread
        Thread.sleep(1000);
        // clean
        listenerSession.getWorkspace().getObservationManager().removeEventListener(listener);
        listenerSession.logout();
    }

    @Test
    @FixFor( "MODE-2379" )
    public void shouldReceiveEventsWhenFilteringBasedOnNodeTypeAndParentsRemoved() throws Exception {
        // register listener
        SimpleListener listener = addListener(2, Event.NODE_REMOVED, null, false, null, new String[]{UNSTRUCTURED}, false);

        // add nodes to be removed
        Node parent = getRoot().addNode("parent", UNSTRUCTURED);
        Node child = parent.addNode("child", UNSTRUCTURED);
        save();

        // remove parent node which removes child node
        String parentPath = parent.getPath();
        String childPath = child.getPath();
        parent.remove();
        save();

        // event handling
        listener.waitForEvents();
        removeListener(listener);

        // tests
        checkResults(listener);
        assertTrue("Path for removed node is wrong", containsPath(listener, parentPath));
        assertTrue("Path for removed child node is wrong", containsPath(listener, childPath));
    }   
    
    @Test
    @FixFor( {"MODE-2572", "MODE-2580"} )
    public void shouldNotFireChangeEventsWhenBinaryDataDoesntChange() throws Exception {
        tools.uploadFile(session, "/file", new ByteArrayInputStream("the quick brown fox".getBytes()));    
        save();

        String nodeTypeFilter = JcrNtLexicon.RESOURCE.getString();
        SimpleListener listener = addListener(2, Event.PROPERTY_CHANGED, null, false, null, new String[]{ nodeTypeFilter}, false);
        // 2 changes expected initially
        Node content = tools.uploadFile(session, "/file", new ByteArrayInputStream("the quick".getBytes())).getNode("jcr:content");
        save();
        listener.waitForEvents();
        removeListener(listener);
        checkResults(listener);
        assertTrue("Path property changed event is wrong", containsPath(listener, "/file/jcr:content/jcr:lastModified"));
        assertTrue("Path property changed event is wrong", containsPath(listener, "/file/jcr:content/jcr:data"));

      
        listener = addListener(1, Event.PROPERTY_CHANGED, null, false, null, new String[]{nodeTypeFilter}, false);
        // set binary to the same value - i.e. a no-op expect NO changes
        content.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream("the quick".getBytes())));
        save();
        listener.waitForEvents();
        removeListener(listener);
        checkResults(listener);
        assertTrue("Path property changed event is wrong", containsPath(listener, "/file/jcr:content/jcr:lastModified"));

        listener = addListener(2, Event.PROPERTY_CHANGED, null, false, null, new String[]{nodeTypeFilter}, false);
        // set binary to a new value and expected 2 events
        content.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream("the quick brown".getBytes())));
        save();
        listener.waitForEvents();
        removeListener(listener);
        checkResults(listener);
        assertTrue("Path property changed event is wrong", containsPath(listener, "/file/jcr:content/jcr:lastModified"));
        assertTrue("Path property changed event is wrong", containsPath(listener, "/file/jcr:content/jcr:data"));
    }

    protected void assertPathsInJournal(EventJournal journal, boolean assertSize, String...expectedPaths) throws RepositoryException {
        assertNotNull("Event journal not configured", journal);
        assertEquals("Event journal size not known upfront", -1, journal.getSize());
        List<String> eventPaths = new ArrayList<>();
        while (journal.hasNext()) {
            Event event = journal.nextEvent();
            eventPaths.add(event.getPath());
        }
        if (assertSize) {
            assertEquals("Incorrect number of events in journal", eventPaths.size(), expectedPaths.length);
        }

        assertTrue(eventPaths.containsAll(Arrays.asList(expectedPaths)));
    }

    protected void assertNoRepositoryNamespace( String uri,
                                                String prefix ) throws RepositoryException {
        NamespaceRegistry registry = session.getWorkspace().getNamespaceRegistry();
        for (String existingPrefix : registry.getPrefixes()) {
            assertThat(existingPrefix.equals(prefix), is(false));
        }
        for (String existingUri : registry.getURIs()) {
            assertThat(existingUri.equals(uri), is(false));
        }
    }

    protected void assertNoSessionNamespace( String uri,
                                             String prefix ) throws RepositoryException {
        for (String existingPrefix : session.getNamespacePrefixes()) {
            assertThat(existingPrefix.equals(prefix), is(false));
            String existingUri = session.getNamespaceURI(existingPrefix);
            assertThat(existingUri.equals(uri), is(false));
        }
    }

    protected VersionHistory versionHistory( Node node ) throws RepositoryException {
        return session.getWorkspace().getVersionManager().getVersionHistory(node.getPath());
    }

    protected Version baseVersion( Node node ) throws RepositoryException {
        return session.getWorkspace().getVersionManager().getBaseVersion(node.getPath());
    }

    protected void lock( Node node,
                         boolean isDeep,
                         boolean isSessionScoped ) throws RepositoryException {
        session.getWorkspace().getLockManager().lock(node.getPath(), isDeep, isSessionScoped, 1L, "owner");
    }
}
