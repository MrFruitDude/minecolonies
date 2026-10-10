package com.minecolonies.core.colony.requestsystem.requests;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport.Payload;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport.TestRequest;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Identity and parent-notification behaviour of {@link AbstractRequest}, on plain fakes.
 */
public class AbstractRequestIdentityTest
{
    /**
     * A child that the parent does not know must not advance the parent: the code warned "log and return" and then
     * carried on.
     */
    @Test
    public void unregisteredChildDoesNotAdvanceTheParent()
    {
        final TestRequest child = new TestRequest(RsTestSupport.token(), new Payload());
        final Map<IToken<?>, IRequest<?>> requests = new HashMap<>();
        requests.put(child.getId(), child);
        final IRequestManager manager = RsTestSupport.manager(new ArrayList<>(), requests, (t, b) -> null);
        child.setState(manager, RequestState.IN_PROGRESS);

        final TestRequest parent = new TestRequest(RsTestSupport.token(), new Payload());
        assertEquals(RequestState.CREATED, parent.getState());

        parent.childStateUpdated(manager, child.getId());

        assertEquals("a child the parent never registered moved the parent to " + parent.getState(), RequestState.CREATED, parent.getState());
    }

    /**
     * The registered case keeps working: the first child entering progress moves the parent along.
     */
    @Test
    public void registeredChildAdvancesTheParent()
    {
        final TestRequest child = new TestRequest(RsTestSupport.token(), new Payload());
        final Map<IToken<?>, IRequest<?>> requests = new HashMap<>();
        requests.put(child.getId(), child);
        final IRequestManager manager = RsTestSupport.manager(new ArrayList<>(), requests, (t, b) -> null);
        child.setState(manager, RequestState.IN_PROGRESS);

        final TestRequest parent = new TestRequest(RsTestSupport.token(), new Payload());
        parent.addChild(child.getId());
        parent.childStateUpdated(manager, child.getId());

        assertEquals(RequestState.IN_PROGRESS, parent.getState());
    }
}
