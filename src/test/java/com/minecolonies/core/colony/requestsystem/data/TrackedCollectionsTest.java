package com.minecolonies.core.colony.requestsystem.data;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.junit.Test;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The view sync trusts the change counter of a store: a store whose counter did not move is not serialized again. So the
 * rule these tests hunt for is: <b>whatever a caller does to a collection a store hands out, if the content changed the
 * counter moved</b>, and the wrapper otherwise behaves exactly like the plain collection it wraps.
 * <p>
 * Random operation sequences (every mutator, the iterators, the views, the sub lists, the nested collections of a map) run
 * on a wrapper and on a plain twin; after every operation the two must hold the same content, answer alike, and if the
 * content changed the counter must have moved.
 */
public class TrackedCollectionsTest
{
    private static final int SEQUENCES = 300;
    private static final int STEPS     = 80;

    private interface Op<T>
    {
        Object apply(T target, int[] p);
    }

    private static final class Named<T>
    {
        final String name;
        final Op<T>  op;

        Named(final String name, final Op<T> op)
        {
            this.name = name;
            this.op = op;
        }
    }

    private static <T> Named<T> op(final String name, final Op<T> op)
    {
        return new Named<>(name, op);
    }

    private static Object run(final Op<?> op, final Object target, final int[] p)
    {
        try
        {
            @SuppressWarnings({"unchecked", "rawtypes"}) final Object result = ((Op) op).apply(target, p);
            return result;
        }
        catch (final RuntimeException e)
        {
            return e.getClass();
        }
    }

    /**
     * Runs random sequences of {@code ops} on a wrapper and on its twin.
     */
    private static <T> void fuzz(final String what, final Supplier<T> twinFactory, final java.util.function.Function<Tracked.StoreVersion, T> wrapperFactory, final List<Named<T>> ops, final long seed)
    {
        final Random random = new Random(seed);
        for (int sequence = 0; sequence < SEQUENCES; sequence++)
        {
            final Tracked.StoreVersion version = new Tracked.StoreVersion();
            final T twin = twinFactory.get();
            final T wrapper = wrapperFactory.apply(version);
            assertEquals(what + ": start", String.valueOf(twin), String.valueOf(wrapper));
            final List<String> history = new ArrayList<>();
            for (int step = 0; step < STEPS; step++)
            {
                final Named<T> named = ops.get(random.nextInt(ops.size()));
                final int[] p = {random.nextInt(8), random.nextInt(8), random.nextInt(8), random.nextInt(8)};
                final String before = String.valueOf(wrapper);
                final long counter = version.get();
                final Object twinResult = run(named.op, twin, p);
                final Object wrapperResult = run(named.op, wrapper, p);
                history.add(named.name + Arrays.toString(p));
                final String context = what + " seed=" + seed + " sequence=" + sequence + " after " + history;
                assertEquals(context + ": the result of " + named.name, resultText(twinResult), resultText(wrapperResult));
                assertEquals(context + ": the content after " + named.name, String.valueOf(twin), String.valueOf(wrapper));
                if (!before.equals(String.valueOf(wrapper)))
                {
                    assertTrue(context + ": " + named.name + " changed the content without moving the counter", version.get() != counter);
                }
            }
        }
    }

    private static String resultText(final Object result)
    {
        if (result instanceof Iterator<?> || result instanceof ListIterator<?>)
        {
            return "iterator";
        }
        return String.valueOf(result);
    }

    // ------------------------------------------------------------------ lists and sets

    private static Integer v(final int[] p, final int i)
    {
        return p[i];
    }

    private static List<Integer> some(final int[] p)
    {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < p[2] % 4; i++)
        {
            out.add((p[0] + i * 3) % 8);
        }
        return out;
    }

    private static <C extends Collection<Integer>> void collectionOps(final List<Named<C>> ops)
    {
        ops.add(op("add", (c, p) -> c.add(v(p, 0))));
        ops.add(op("addAll", (c, p) -> c.addAll(some(p))));
        ops.add(op("remove(Object)", (c, p) -> c.remove(v(p, 0))));
        ops.add(op("removeAll", (c, p) -> c.removeAll(some(p))));
        ops.add(op("retainAll", (c, p) -> c.retainAll(some(p))));
        ops.add(op("removeIf", (c, p) -> c.removeIf(x -> x % 4 == p[0] % 4)));
        ops.add(op("clear", (c, p) -> {
            if (p[3] == 0)
            {
                c.clear();
            }
            return null;
        }));
        ops.add(op("iterator.remove", (c, p) -> {
            final Iterator<Integer> it = c.iterator();
            Integer last = null;
            for (int i = 0; i <= p[0] % 5 && it.hasNext(); i++)
            {
                last = it.next();
            }
            if (last != null)
            {
                it.remove();
            }
            return last;
        }));
        ops.add(op("iterator.forEachRemaining+remove", (c, p) -> {
            final Iterator<Integer> it = c.iterator();
            int n = 0;
            while (it.hasNext())
            {
                if (it.next() % 3 == p[1] % 3 && n++ < 2)
                {
                    it.remove();
                }
            }
            return n;
        }));
        ops.add(op("stream/contains (reads)", (c, p) -> c.contains(v(p, 0)) + ":" + c.size() + ":" + c.stream().mapToInt(Integer::intValue).sum() + ":" + c.containsAll(some(p))));
    }

    private static <L extends List<Integer>> void listOps(final List<Named<L>> ops)
    {
        ops.add(op("add(i,e)", (l, p) -> {
            l.add(p[0] % (l.size() + 1), v(p, 1));
            return null;
        }));
        ops.add(op("addAll(i,c)", (l, p) -> l.addAll(p[0] % (l.size() + 1), some(p))));
        ops.add(op("remove(int)", (l, p) -> l.remove(p[0])));
        ops.add(op("set", (l, p) -> l.set(p[0], v(p, 1))));
        ops.add(op("replaceAll", (l, p) -> {
            l.replaceAll(x -> (x + p[0]) % 8);
            return null;
        }));
        ops.add(op("sort", (l, p) -> {
            l.sort(p[0] % 2 == 0 ? Comparator.naturalOrder() : Comparator.reverseOrder());
            return null;
        }));
        ops.add(op("Collections.reverse", (l, p) -> {
            Collections.reverse(l);
            return null;
        }));
        ops.add(op("Collections.swap", (l, p) -> {
            Collections.swap(l, p[0], p[1]);
            return null;
        }));
        ops.add(op("Collections.rotate", (l, p) -> {
            Collections.rotate(l, p[0]);
            return null;
        }));
        ops.add(op("listIterator.set/add/remove", (l, p) -> {
            final ListIterator<Integer> it = l.listIterator(p[3] % (l.size() + 1));
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < 3; i++)
            {
                switch ((p[0] + i) % 4)
                {
                    case 0 -> {
                        if (it.hasNext())
                        {
                            out.append(it.next());
                            it.set(p[1]);
                        }
                    }
                    case 1 -> it.add(p[2]);
                    case 2 -> {
                        if (it.hasPrevious())
                        {
                            out.append(it.previous());
                            it.remove();
                        }
                    }
                    default -> {
                        if (it.hasNext())
                        {
                            out.append(it.next());
                            it.remove();
                        }
                    }
                }
            }
            return out.toString();
        }));
        ops.add(op("subList.clear", (l, p) -> {
            final int a = p[0] % (l.size() + 1);
            final int b = a + p[1] % (l.size() - a + 1);
            l.subList(a, b).clear();
            return null;
        }));
        ops.add(op("subList.add/remove/set", (l, p) -> {
            final int a = p[0] % (l.size() + 1);
            final int b = a + p[1] % (l.size() - a + 1);
            final List<Integer> sub = l.subList(a, b);
            sub.add(p[2]);
            if (!sub.isEmpty())
            {
                sub.set(0, p[3]);
                sub.remove(sub.size() - 1);
            }
            sub.removeIf(x -> x == p[2]);
            return sub.size();
        }));
        ops.add(op("subList.sort/replaceAll", (l, p) -> {
            final int a = p[0] % (l.size() + 1);
            final int b = a + p[1] % (l.size() - a + 1);
            final List<Integer> sub = l.subList(a, b);
            sub.sort(Comparator.reverseOrder());
            sub.replaceAll(x -> (x + 1) % 8);
            return null;
        }));
        ops.add(op("addFirst/addLast", (l, p) -> {
            if (p[0] % 2 == 0)
            {
                l.addFirst(p[1]);
            }
            else
            {
                l.addLast(p[1]);
            }
            return null;
        }));
        ops.add(op("removeFirst/removeLast", (l, p) -> p[0] % 2 == 0 ? l.removeFirst() : l.removeLast()));
        ops.add(op("reversed().add/remove/set", (l, p) -> {
            final List<Integer> reversed = l.reversed();
            reversed.add(p[0]);
            if (!reversed.isEmpty())
            {
                reversed.set(0, p[1]);
                reversed.remove(reversed.size() - 1);
            }
            return reversed.size();
        }));
        ops.add(op("indexOf (reads)", (l, p) -> l.indexOf(p[0]) + ":" + l.lastIndexOf(p[0]) + ":" + (l.isEmpty() ? "-" : l.get(0))));
    }

    @Test
    public void trackedListKeepsCountingAndBehavesLikeAnArrayList()
    {
        final List<Named<List<Integer>>> ops = new ArrayList<>();
        collectionOps(ops);
        listOps(ops);
        fuzz("list", () -> new ArrayList<>(List.of(1, 2, 3)), version -> Tracked.list(version, List.of(1, 2, 3)), ops, 1);
    }

    @Test
    public void trackedLinkedListKeepsCountingAndBehavesLikeALinkedList()
    {
        final List<Named<LinkedList<Integer>>> ops = new ArrayList<>();
        collectionOps(ops);
        listOps(ops);
        ops.add(op("push/pop", (l, p) -> {
            if (p[0] % 2 == 0)
            {
                l.push(p[1]);
                return null;
            }
            return l.pop();
        }));
        ops.add(op("offer*", (l, p) -> p[0] % 3 == 0 ? l.offer(p[1]) : p[0] % 3 == 1 ? l.offerFirst(p[1]) : l.offerLast(p[1])));
        ops.add(op("poll*", (l, p) -> p[0] % 3 == 0 ? l.poll() : p[0] % 3 == 1 ? l.pollFirst() : l.pollLast()));
        ops.add(op("remove()", (l, p) -> l.remove()));
        ops.add(op("removeFirst/LastOccurrence", (l, p) -> p[0] % 2 == 0 ? l.removeFirstOccurrence(p[1]) : l.removeLastOccurrence(p[1])));
        ops.add(op("descendingIterator.remove", (l, p) -> {
            final Iterator<Integer> it = l.descendingIterator();
            Integer last = null;
            for (int i = 0; i <= p[0] % 4 && it.hasNext(); i++)
            {
                last = it.next();
            }
            if (last != null)
            {
                it.remove();
            }
            return last;
        }));
        ops.add(op("linked reversed().push/pop/removeIf", (l, p) -> {
            final LinkedList<Integer> reversed = l.reversed();
            reversed.addFirst(p[0]);
            if (!reversed.isEmpty())
            {
                reversed.removeLast();
            }
            reversed.removeIf(x -> x == p[1]);
            return reversed.size();
        }));
        ops.add(op("addAll(c) on empty", (l, p) -> l.addAll(some(p))));
        fuzz("linked", () -> new LinkedList<>(List.of(1, 2, 3)), version -> Tracked.queue(version, List.of(1, 2, 3)), ops, 2);
    }

    @Test
    public void trackedSetKeepsCountingAndBehavesLikeAHashSet()
    {
        final List<Named<Set<Integer>>> ops = new ArrayList<>();
        collectionOps(ops);
        fuzz("set", () -> new HashSet<>(List.of(1, 2, 3)), version -> Tracked.set(version, List.of(1, 2, 3)), ops, 3);
    }

    @Test
    public void wrappedCollectionsKeepTheirKind()
    {
        final Tracked.StoreVersion version = new Tracked.StoreVersion();
        final Collection<Integer> list = Tracked.wrap(new ArrayList<>(List.of(1)), version);
        final Collection<Integer> set = Tracked.wrap(new HashSet<>(List.of(1)), version);
        final Collection<Integer> immutable = Tracked.wrap(List.of(1), version);
        assertTrue("a list stays a list", list instanceof List);
        assertTrue("a set stays a set", set instanceof Set);
        assertTrue("an immutable list stays a list", immutable instanceof List);
        assertTrue("wrapping twice does not stack", Tracked.wrap(list, version) == list);
        final long before = version.get();
        try
        {
            immutable.add(2);
        }
        catch (final UnsupportedOperationException expected)
        {
            // an immutable collection refuses, and nothing changed
        }
        assertEquals("a refused change does not count", before, version.get());
        assertTrue("a set refuses a duplicate", !set.add(1));
        assertEquals("...without counting", before, version.get());
    }

    // ------------------------------------------------------------------ maps

    private static void mapOps(final List<Named<Map<Integer, Collection<Integer>>>> ops, final Supplier<Collection<Integer>> nested)
    {
        ops.add(op("put", (m, p) -> m.put(p[0], fresh(nested, p))));
        ops.add(op("putAll", (m, p) -> {
            final Map<Integer, Collection<Integer>> more = new HashMap<>();
            more.put(p[0], fresh(nested, p));
            more.put(p[1], fresh(nested, p));
            m.putAll(more);
            return null;
        }));
        ops.add(op("remove(key)", (m, p) -> m.remove(p[0])));
        ops.add(op("remove(key,value)", (m, p) -> m.remove(p[0], m.get(p[0]))));
        ops.add(op("clear", (m, p) -> {
            if (p[3] == 0)
            {
                m.clear();
            }
            return null;
        }));
        ops.add(op("putIfAbsent", (m, p) -> m.putIfAbsent(p[0], fresh(nested, p))));
        ops.add(op("computeIfAbsent", (m, p) -> m.computeIfAbsent(p[0], k -> fresh(nested, p))));
        ops.add(op("computeIfAbsent(ArrayList::new)", (m, p) -> m.computeIfAbsent(p[0], k -> new ArrayList<>(k)).add(p[1])));
        ops.add(op("computeIfPresent", (m, p) -> m.computeIfPresent(p[0], (k, old) -> p[1] % 2 == 0 ? fresh(nested, p) : null)));
        ops.add(op("compute", (m, p) -> m.compute(p[0], (k, old) -> p[1] % 2 == 0 ? fresh(nested, p) : null)));
        ops.add(op("merge", (m, p) -> m.merge(p[0], fresh(nested, p), (a, b) -> {
            final Collection<Integer> out = nested.get();
            out.addAll(a);
            out.addAll(b);
            return out;
        })));
        ops.add(op("replace(k,v)", (m, p) -> m.replace(p[0], fresh(nested, p))));
        ops.add(op("replace(k,old,new)", (m, p) -> m.replace(p[0], m.get(p[0]), fresh(nested, p))));
        ops.add(op("replaceAll", (m, p) -> {
            m.replaceAll((k, old) -> k % 2 == p[0] % 2 ? fresh(nested, p) : old);
            return null;
        }));
        ops.add(op("get(k).add", (m, p) -> m.containsKey(p[0]) && m.get(p[0]).add(p[1])));
        ops.add(op("get(k).remove", (m, p) -> m.containsKey(p[0]) && m.get(p[0]).remove(p[1])));
        ops.add(op("get(k).addAll/removeAll/retainAll/removeIf", (m, p) -> {
            if (!m.containsKey(p[0]))
            {
                return null;
            }
            final Collection<Integer> c = m.get(p[0]);
            return c.addAll(some(p)) + ":" + c.removeAll(some(new int[] {p[1], p[2], p[3], p[0]})) + ":" + c.retainAll(some(new int[] {p[3], p[2], p[1], p[0]})) + ":" + c.removeIf(x -> x == p[2]);
        }));
        ops.add(op("get(k).iterator.remove", (m, p) -> {
            if (!m.containsKey(p[0]))
            {
                return null;
            }
            final Iterator<Integer> it = m.get(p[0]).iterator();
            Integer last = null;
            while (it.hasNext())
            {
                last = it.next();
                if (last % 2 == p[1] % 2)
                {
                    it.remove();
                    break;
                }
            }
            return last;
        }));
        ops.add(op("get(k).clear", (m, p) -> {
            if (m.containsKey(p[0]) && p[1] == 0)
            {
                m.get(p[0]).clear();
            }
            return null;
        }));
        ops.add(op("get(k) list ops", (m, p) -> {
            if (!m.containsKey(p[0]) || !(m.get(p[0]) instanceof List<Integer> l))
            {
                return null;
            }
            l.add(p[1] % (l.size() + 1), p[2]);
            if (!l.isEmpty())
            {
                l.set(0, p[3]);
                l.remove(l.size() - 1);
            }
            l.subList(0, l.size() / 2).clear();
            return l.size();
        }));
        ops.add(op("entrySet.iterator.remove", (m, p) -> {
            final Iterator<Map.Entry<Integer, Collection<Integer>>> it = m.entrySet().iterator();
            Integer last = null;
            for (int i = 0; i <= p[0] % 4 && it.hasNext(); i++)
            {
                last = it.next().getKey();
            }
            if (last != null)
            {
                it.remove();
            }
            return last == null ? null : "removed";
        }));
        ops.add(op("entry.setValue", (m, p) -> {
            for (final Map.Entry<Integer, Collection<Integer>> e : m.entrySet())
            {
                if (e.getKey() % 3 == p[0] % 3)
                {
                    e.setValue(fresh(nested, p));
                    break;
                }
            }
            return null;
        }));
        ops.add(op("entry.getValue().add", (m, p) -> {
            for (final Map.Entry<Integer, Collection<Integer>> e : m.entrySet())
            {
                if (e.getKey() % 3 == p[0] % 3)
                {
                    return e.getValue().add(p[1]);
                }
            }
            return null;
        }));
        ops.add(op("keySet.remove/removeIf/retainAll", (m, p) -> m.keySet().remove(p[0]) + ":" + m.keySet().removeIf(k -> k == p[1]) + ":" + (p[2] == 0 && m.keySet().retainAll(some(p)))));
        ops.add(op("keySet.clear", (m, p) -> {
            if (p[3] == 0)
            {
                m.keySet().clear();
            }
            return null;
        }));
        ops.add(op("values.removeIf/remove", (m, p) -> m.values().removeIf(c -> c.contains(p[0])) + ":" + (p[1] == 0 && m.values().remove(m.get(p[2])))));
        ops.add(op("values.clear", (m, p) -> {
            if (p[3] == 0)
            {
                m.values().clear();
            }
            return null;
        }));
        ops.add(op("entrySet.removeIf/remove/clear", (m, p) -> m.entrySet().removeIf(e -> e.getKey() == p[0]) + ":" + (p[1] == 0 && !m.isEmpty() && m.entrySet().remove(m.entrySet().iterator().next()))
                                                                    + ":" + (p[3] == 0 && clearEntries(m))));
        ops.add(op("reads", (m, p) -> m.containsKey(p[0]) + ":" + m.getOrDefault(p[1], null) + ":" + m.size() + ":" + m.containsValue(m.get(p[2]))));
    }

    private static boolean clearEntries(final Map<Integer, Collection<Integer>> m)
    {
        m.entrySet().clear();
        return true;
    }

    private static Collection<Integer> fresh(final Supplier<Collection<Integer>> nested, final int[] p)
    {
        final Collection<Integer> out = nested.get();
        out.addAll(some(new int[] {p[1], p[2], p[3] + 1, p[0]}));
        return out;
    }

    private static String sorted(final Map<Integer, Collection<Integer>> map)
    {
        return new TreeMap<>(map).toString();
    }

    /**
     * Map content in a form that does not depend on hash order (the twin and the wrapper may be built in another order).
     */
    private static void mapFuzz(final String what, final Supplier<Map<Integer, Collection<Integer>>> twin, final java.util.function.Function<Tracked.StoreVersion, Map<Integer, Collection<Integer>>> wrapper,
      final Supplier<Collection<Integer>> nested, final long seed)
    {
        final List<Named<Map<Integer, Collection<Integer>>>> ops = new ArrayList<>();
        mapOps(ops, nested);
        fuzz(what, twin, wrapper, ops, seed);
    }

    @Test
    public void trackedMapOfListsOverAHashMap()
    {
        mapFuzz("map of lists", () -> new TreeMap<>(), version -> Tracked.collectionMap(version, new TreeMap<>(), Map.of()), ArrayList::new, 4);
    }

    @Test
    public void trackedMapOfSetsOverAHashMap()
    {
        mapFuzz("map of sets", () -> new TreeMap<>(), version -> Tracked.collectionMap(version, new TreeMap<>(), Map.of()), HashSet::new, 5);
    }

    @Test
    public void trackedMapOfLinkedListsOverAnIntMap()
    {
        // fastutil's int map, as the building store keeps its per-citizen requests (a sorted view of both sides compares the content)
        final Random random = new Random(6);
        for (int sequence = 0; sequence < SEQUENCES; sequence++)
        {
            final Tracked.StoreVersion version = new Tracked.StoreVersion();
            final Map<Integer, Collection<Integer>> wrapper = Tracked.collectionMap(version, new Int2ObjectOpenHashMap<Collection<Integer>>(), Map.of());
            final Map<Integer, Collection<Integer>> twin = new Int2ObjectOpenHashMap<>();
            final List<Named<Map<Integer, Collection<Integer>>>> ops = new ArrayList<>();
            mapOps(ops, LinkedList::new);
            for (int step = 0; step < STEPS; step++)
            {
                final Named<Map<Integer, Collection<Integer>>> named = ops.get(random.nextInt(ops.size()));
                final int[] p = {random.nextInt(8), random.nextInt(8), random.nextInt(8), random.nextInt(8)};
                final String before = sorted(wrapper);
                final long counter = version.get();
                final Object twinResult = run(named.op, twin, p);
                final Object wrapperResult = run(named.op, wrapper, p);
                final String context = "int map sequence " + sequence + " step " + step + " " + named.name + Arrays.toString(p);
                assertEquals(context + ": result", resultText(twinResult), resultText(wrapperResult));
                assertEquals(context + ": content", sorted(twin), sorted(wrapper));
                if (!before.equals(sorted(wrapper)))
                {
                    assertTrue(context + ": changed the content without moving the counter", version.get() != counter);
                }
            }
        }
    }

    @Test
    public void trackedPlainValueMapOverAnObjectIntMap()
    {
        final Random random = new Random(7);
        for (int sequence = 0; sequence < SEQUENCES; sequence++)
        {
            final Tracked.StoreVersion version = new Tracked.StoreVersion();
            final Map<String, Integer> wrapper = Tracked.map(version, new Object2IntOpenHashMap<String>(), Map.of());
            final Map<String, Integer> twin = new Object2IntOpenHashMap<>();
            for (int step = 0; step < STEPS; step++)
            {
                final String key = "k" + random.nextInt(6);
                final int value = random.nextInt(5);
                final String before = new TreeMap<>(wrapper).toString();
                final long counter = version.get();
                final int which = random.nextInt(8);
                final Object a = plainOp(twin, which, key, value);
                final Object b = plainOp(wrapper, which, key, value);
                final String context = "plain map sequence " + sequence + " step " + step + " op " + which + " " + key + " " + value;
                assertEquals(context + ": result", String.valueOf(a), String.valueOf(b));
                assertEquals(context + ": content", new TreeMap<>(twin).toString(), new TreeMap<>(wrapper).toString());
                if (!before.equals(new TreeMap<>(wrapper).toString()))
                {
                    assertTrue(context + ": changed the content without moving the counter", version.get() != counter);
                }
            }
        }
    }

    private static Object plainOp(final Map<String, Integer> m, final int which, final String key, final int value)
    {
        switch (which)
        {
            case 0:
                return m.put(key, value);
            case 1:
                return m.remove(key);
            case 2:
                return m.merge(key, value, Integer::sum);
            case 3:
                return m.computeIfAbsent(key, k -> value);
            case 4:
                return m.replace(key, value);
            case 5:
            {
                final Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator();
                if (it.hasNext())
                {
                    it.next().setValue(value);
                }
                return null;
            }
            case 6:
                return m.keySet().remove(key);
            default:
                return m.getOrDefault(key, -1) + ":" + m.containsKey(key);
        }
    }

    @Test
    public void aCollectionPutIntoAMapIsTakenOver()
    {
        final Tracked.StoreVersion version = new Tracked.StoreVersion();
        final Map<Integer, Collection<Integer>> map = Tracked.collectionMap(version, new HashMap<>(), Map.of());
        final List<Integer> mine = new ArrayList<>(List.of(1, 2));
        map.put(1, mine);
        final long before = version.get();
        map.get(1).add(3);
        assertTrue("changing what the map hands out counts", version.get() != before);
        assertEquals(List.of(1, 2, 3), new ArrayList<>(map.get(1)));
    }
}
