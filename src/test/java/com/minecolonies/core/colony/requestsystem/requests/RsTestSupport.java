package com.minecolonies.core.colony.requestsystem.requests;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IRequestable;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Plain-JVM fakes for the request system: no Minecraft bootstrap and no colony needed.
 */
public final class RsTestSupport
{
    private RsTestSupport()
    {
    }

    /**
     * A requestable with no payload.
     */
    public static final class Payload implements IRequestable
    {
        @Override
        public Set<TypeToken<?>> getSuperClasses()
        {
            return Set.of();
        }
    }

    /**
     * Minimal concrete request.
     */
    public static final class TestRequest extends AbstractRequest<Payload>
    {
        public TestRequest(final IToken<?> token, final Payload payload)
        {
            super(requester(), token, payload);
        }

        @Override
        public Component getShortDisplayString()
        {
            return null;
        }
    }

    /**
     * A requester that only supports the Object methods.
     */
    public static IRequester requester()
    {
        return (IRequester) Proxy.newProxyInstance(RsTestSupport.class.getClassLoader(), new Class<?>[] {IRequester.class}, (proxy, method, args) -> switch (method.getName())
        {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "TestRequester";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    public static IToken<?> token()
    {
        return new StandardToken();
    }

    /**
     * Fake request manager: records {@code log} calls, resolves {@code getRequestForToken} from the given map, answers
     * {@code reassignRequest} through the given function, and refuses everything else.
     */
    public static IRequestManager manager(
      final List<String> logs,
      final Map<IToken<?>, IRequest<?>> requests,
      final BiFunction<IToken<?>, Object, IToken<?>> reassign)
    {
        final InvocationHandler handler = (proxy, method, args) -> switch (method.getName())
        {
            case "log" ->
            {
                logs.add((String) args[0]);
                yield null;
            }
            case "getRequestForToken" -> requests.get(args[0]);
            case "reassignRequest" -> reassign.apply((IToken<?>) args[0], args[1]);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "FakeRequestManager";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (IRequestManager) Proxy.newProxyInstance(RsTestSupport.class.getClassLoader(), new Class<?>[] {IRequestManager.class}, handler);
    }

    public static IRequestManager manager()
    {
        return manager(new ArrayList<>(), new HashMap<>(), (t, b) -> null);
    }
}
