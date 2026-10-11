package com.minecolonies.core.colony.requestsystem.wait;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RS2: warnings can be routed to a sink (Colony Command's notification queue) instead of the chat.
 */
public class RequestNotifierSinkTest
{
    private static final IColony COLONY = (IColony) Proxy.newProxyInstance(RequestNotifierSinkTest.class.getClassLoader(), new Class<?>[] {IColony.class},
      (proxy, method, args) -> method.getName().equals("hashCode") ? 1 : method.getName().equals("equals") ? proxy == args[0] : null);

    @After
    public void clearSink()
    {
        RequestNotifier.setSink(null);
    }

    private static RsTestSupport.TestRequest request()
    {
        return new RsTestSupport.TestRequest(RsTestSupport.token(), new RsTestSupport.Payload());
    }

    @Test
    public void theSinkSeesEachTopicOncePerFiveMinutesAndItsSendCountsAsSent()
    {
        final List<WaitReason> got = new ArrayList<>();
        RequestNotifier.setSink((colony, req, reason, item, now) -> got.add(reason));
        final RequestNotifier notifier = new RequestNotifier();

        assertTrue(notifier.report(COLONY, request(), WaitReason.NO_CRAFTER, 100));
        // Another request, same topic, inside five minutes: limited.
        assertFalse(notifier.report(COLONY, request(), WaitReason.NO_CRAFTER, 200));
        // Same request again: deduplicated.
        final RsTestSupport.TestRequest other = request();
        assertTrue(notifier.report(COLONY, other, WaitReason.NO_COURIER, 300));
        assertFalse(notifier.report(COLONY, other, WaitReason.NO_COURIER, 400));
        // After five minutes the topic may be reported again.
        assertTrue(notifier.report(COLONY, request(), WaitReason.NO_CRAFTER, 100 + RequestNotifier.REPEAT_TICKS));

        assertEquals(List.of(WaitReason.NO_CRAFTER, WaitReason.NO_COURIER, WaitReason.NO_CRAFTER), got);
        assertEquals(3, notifier.sentCount());
        assertEquals(3, notifier.recent().size());
    }

    @Test
    public void infoReasonsNeverReachTheSink()
    {
        final List<WaitReason> got = new ArrayList<>();
        RequestNotifier.setSink((colony, req, reason, item, now) -> got.add(reason));
        final RequestNotifier notifier = new RequestNotifier();
        for (final WaitReason reason : WaitReason.values())
        {
            if (reason.severity() != WaitReason.Severity.WARNING)
            {
                assertFalse(notifier.report(COLONY, request(), reason, 0));
            }
        }
        assertTrue(got.isEmpty());
    }

    @Test
    public void aSinkThatDeclinesLeavesTheChatPathToRun()
    {
        final int[] calls = {0};
        RequestNotifier.setSink((colony, req, reason, item, now) -> {
            calls[0]++;
            return false;
        });
        final RequestNotifier notifier = new RequestNotifier();
        // The fake colony is not a server colony, so the chat path is a no-op here; the report still counts.
        assertTrue(notifier.report(COLONY, request(), WaitReason.PLAYER_REQUIRED, 0));
        assertEquals(1, calls[0]);
    }
}
