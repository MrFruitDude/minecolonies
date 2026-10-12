package com.minecolonies.core.colony.requestsystem.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Collections that count their own changes.
 * <p>
 * The request-system data stores hand out live collections: the request manager, the buildings and the jobs change them
 * from dozens of places. The view sync wants to know whether a store changed since it last wrote it, so that a store that
 * did not change is not serialized again. Instead of finding and instrumenting every call site, every collection a store
 * hands out is one of these, and every way to change one (the methods, the iterators, the views, the sub lists) bumps the
 * store's {@link StoreVersion}. A change that ends up changing nothing may bump too (a false alarm costs one
 * serialization); a change that does not bump is a bug, which {@code TrackedCollectionsTest} hunts with random operations.
 * <p>
 * The wrappers delegate to the collection they were given and keep its semantics (order, duplicates, hash or list lookups).
 * They take that collection over: it must not be changed behind their back.
 */
public final class Tracked
{
    private Tracked()
    {
    }

    /**
     * The change counter of one store. Only ever compared for equality with an earlier reading.
     */
    public static final class StoreVersion
    {
        private long value;

        public long get()
        {
            return value;
        }

        public void bump()
        {
            value++;
        }
    }

    /**
     * Marks the wrappers, so that a wrapper is never wrapped again.
     */
    private interface Wrapper
    {
        StoreVersion version();
    }

    // ------------------------------------------------------------------ factories

    /**
     * @return {@code inner} as a collection that counts its changes (a list stays a list, a set stays a set).
     */
    @Nullable
    public static <E> Collection<E> wrap(@Nullable final Collection<E> inner, final StoreVersion version)
    {
        if (inner == null)
        {
            return null;
        }
        if (inner instanceof final Wrapper wrapper && wrapper.version() == version)
        {
            return inner;
        }
        if (inner instanceof List<E> list)
        {
            return new TrackedList<>(list, version);
        }
        if (inner instanceof Set<E> set)
        {
            return new TrackedSet<>(set, version);
        }
        return new TrackedCollection<>(inner, version);
    }

    /**
     * @return a list that counts its changes, with the content of {@code content}.
     */
    public static <E> List<E> list(final StoreVersion version, final Collection<? extends E> content)
    {
        return new TrackedList<>(new ArrayList<>(content), version);
    }

    /**
     * @return a set that counts its changes, with the content of {@code content}.
     */
    public static <E> Set<E> set(final StoreVersion version, final Collection<? extends E> content)
    {
        return new TrackedSet<>(new HashSet<>(content), version);
    }

    /**
     * @return a linked list that counts its changes, with the content of {@code content}.
     */
    public static <E> LinkedList<E> queue(final StoreVersion version, final Collection<? extends E> content)
    {
        final TrackedLinkedList<E> queue = new TrackedLinkedList<>(version);
        queue.addAll(content);
        return queue;
    }

    /**
     * A map that counts its changes, over {@code backing} (which it takes over). Collections put into it are wrapped, and
     * the wrapper is what the map gives back.
     *
     * @param backing the storage; must be empty and not used by anyone else.
     * @param content what the map starts with.
     */
    public static <K, V extends Collection<?>> Map<K, V> collectionMap(final StoreVersion version, final Map<K, V> backing, final Map<? extends K, ? extends V> content)
    {
        final TrackedMap<K, V> map = new TrackedMap<>(backing, version, TrackedMap.collectionWrapper(version));
        map.putAll(content);
        return map;
    }

    /**
     * A map of plain values that counts its changes, over {@code backing} (which it takes over).
     */
    public static <K, V> Map<K, V> map(final StoreVersion version, final Map<K, V> backing, final Map<? extends K, ? extends V> content)
    {
        final TrackedMap<K, V> map = new TrackedMap<>(backing, version, null);
        map.putAll(content);
        return map;
    }

    // ------------------------------------------------------------------ iterators

    private static final class TrackedIterator<E> implements ListIterator<E>
    {
        private final ListIterator<E> inner;
        private final StoreVersion    version;

        private TrackedIterator(final ListIterator<E> inner, final StoreVersion version)
        {
            this.inner = inner;
            this.version = version;
        }

        @Override
        public boolean hasNext()
        {
            return inner.hasNext();
        }

        @Override
        public E next()
        {
            return inner.next();
        }

        @Override
        public boolean hasPrevious()
        {
            return inner.hasPrevious();
        }

        @Override
        public E previous()
        {
            return inner.previous();
        }

        @Override
        public int nextIndex()
        {
            return inner.nextIndex();
        }

        @Override
        public int previousIndex()
        {
            return inner.previousIndex();
        }

        @Override
        public void remove()
        {
            inner.remove();
            version.bump();
        }

        @Override
        public void set(final E e)
        {
            inner.set(e);
            version.bump();
        }

        @Override
        public void add(final E e)
        {
            inner.add(e);
            version.bump();
        }

        @Override
        public void forEachRemaining(final Consumer<? super E> action)
        {
            inner.forEachRemaining(action);
        }
    }

    private static final class TrackedPlainIterator<E> implements Iterator<E>
    {
        private final Iterator<E>  inner;
        private final StoreVersion version;

        private TrackedPlainIterator(final Iterator<E> inner, final StoreVersion version)
        {
            this.inner = inner;
            this.version = version;
        }

        @Override
        public boolean hasNext()
        {
            return inner.hasNext();
        }

        @Override
        public E next()
        {
            return inner.next();
        }

        @Override
        public void remove()
        {
            inner.remove();
            version.bump();
        }

        @Override
        public void forEachRemaining(final Consumer<? super E> action)
        {
            inner.forEachRemaining(action);
        }
    }

    // ------------------------------------------------------------------ plain collection

    private static final class TrackedCollection<E> extends AbstractCollection<E> implements Wrapper
    {
        private final Collection<E> inner;
        private final StoreVersion  version;

        private TrackedCollection(final Collection<E> inner, final StoreVersion version)
        {
            this.inner = inner;
            this.version = version;
        }

        @Override
        public StoreVersion version()
        {
            return version;
        }

        @Override
        public boolean add(final E e)
        {
            if (inner.add(e))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean remove(final Object o)
        {
            if (inner.remove(o))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean addAll(final @NotNull Collection<? extends E> c)
        {
            if (inner.addAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeAll(final @NotNull Collection<?> c)
        {
            if (inner.removeAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean retainAll(final @NotNull Collection<?> c)
        {
            if (inner.retainAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeIf(final @NotNull Predicate<? super E> filter)
        {
            if (inner.removeIf(filter))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            if (!inner.isEmpty())
            {
                inner.clear();
                version.bump();
            }
        }

        @Override
        public boolean contains(final Object o)
        {
            return inner.contains(o);
        }

        @Override
        public boolean containsAll(final @NotNull Collection<?> c)
        {
            return inner.containsAll(c);
        }

        @Override
        public int size()
        {
            return inner.size();
        }

        @Override
        public boolean isEmpty()
        {
            return inner.isEmpty();
        }

        @Override
        public @NotNull Iterator<E> iterator()
        {
            return new TrackedPlainIterator<>(inner.iterator(), version);
        }

        @Override
        public void forEach(final Consumer<? super E> action)
        {
            inner.forEach(action);
        }

        @Override
        public Object[] toArray()
        {
            return inner.toArray();
        }

        @Override
        public boolean equals(final Object o)
        {
            return o == this || inner.equals(o instanceof final TrackedCollection<?> other ? other.inner : o);
        }

        @Override
        public int hashCode()
        {
            return inner.hashCode();
        }

        @Override
        public String toString()
        {
            return inner.toString();
        }
    }

    // ------------------------------------------------------------------ set

    private static final class TrackedSet<E> extends AbstractSet<E> implements Wrapper
    {
        private final Set<E>       inner;
        private final StoreVersion version;

        private TrackedSet(final Set<E> inner, final StoreVersion version)
        {
            this.inner = inner;
            this.version = version;
        }

        @Override
        public StoreVersion version()
        {
            return version;
        }

        @Override
        public boolean add(final E e)
        {
            if (inner.add(e))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean remove(final Object o)
        {
            if (inner.remove(o))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean addAll(final @NotNull Collection<? extends E> c)
        {
            if (inner.addAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeAll(final @NotNull Collection<?> c)
        {
            if (inner.removeAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean retainAll(final @NotNull Collection<?> c)
        {
            if (inner.retainAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeIf(final @NotNull Predicate<? super E> filter)
        {
            if (inner.removeIf(filter))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            if (!inner.isEmpty())
            {
                inner.clear();
                version.bump();
            }
        }

        @Override
        public boolean contains(final Object o)
        {
            return inner.contains(o);
        }

        @Override
        public boolean containsAll(final @NotNull Collection<?> c)
        {
            return inner.containsAll(c);
        }

        @Override
        public int size()
        {
            return inner.size();
        }

        @Override
        public boolean isEmpty()
        {
            return inner.isEmpty();
        }

        @Override
        public @NotNull Iterator<E> iterator()
        {
            return new TrackedPlainIterator<>(inner.iterator(), version);
        }

        @Override
        public void forEach(final Consumer<? super E> action)
        {
            inner.forEach(action);
        }

        @Override
        public Object[] toArray()
        {
            return inner.toArray();
        }

        @Override
        public String toString()
        {
            return inner.toString();
        }
    }

    // ------------------------------------------------------------------ list

    private static final class TrackedList<E> extends AbstractList<E> implements Wrapper
    {
        private final List<E>      inner;
        private final StoreVersion version;

        private TrackedList(final List<E> inner, final StoreVersion version)
        {
            this.inner = inner;
            this.version = version;
        }

        @Override
        public StoreVersion version()
        {
            return version;
        }

        @Override
        public E get(final int index)
        {
            return inner.get(index);
        }

        @Override
        public E set(final int index, final E element)
        {
            final E old = inner.set(index, element);
            version.bump();
            return old;
        }

        @Override
        public void add(final int index, final E element)
        {
            inner.add(index, element);
            version.bump();
        }

        @Override
        public boolean add(final E e)
        {
            if (inner.add(e))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public E remove(final int index)
        {
            final E old = inner.remove(index);
            version.bump();
            return old;
        }

        @Override
        public boolean remove(final Object o)
        {
            if (inner.remove(o))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean addAll(final @NotNull Collection<? extends E> c)
        {
            if (inner.addAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean addAll(final int index, final @NotNull Collection<? extends E> c)
        {
            if (inner.addAll(index, c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeAll(final @NotNull Collection<?> c)
        {
            if (inner.removeAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean retainAll(final @NotNull Collection<?> c)
        {
            if (inner.retainAll(c))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public boolean removeIf(final @NotNull Predicate<? super E> filter)
        {
            if (inner.removeIf(filter))
            {
                version.bump();
                return true;
            }
            return false;
        }

        @Override
        public void replaceAll(final UnaryOperator<E> operator)
        {
            inner.replaceAll(operator);
            version.bump();
        }

        @Override
        public void sort(final Comparator<? super E> c)
        {
            inner.sort(c);
            version.bump();
        }

        @Override
        public void clear()
        {
            if (!inner.isEmpty())
            {
                inner.clear();
                version.bump();
            }
        }

        @Override
        protected void removeRange(final int fromIndex, final int toIndex)
        {
            if (fromIndex < toIndex)
            {
                inner.subList(fromIndex, toIndex).clear();
                version.bump();
            }
        }

        @Override
        public boolean contains(final Object o)
        {
            return inner.contains(o);
        }

        @Override
        public int indexOf(final Object o)
        {
            return inner.indexOf(o);
        }

        @Override
        public int lastIndexOf(final Object o)
        {
            return inner.lastIndexOf(o);
        }

        @Override
        public int size()
        {
            return inner.size();
        }

        @Override
        public boolean isEmpty()
        {
            return inner.isEmpty();
        }

        @Override
        public @NotNull Iterator<E> iterator()
        {
            return new TrackedIterator<>(inner.listIterator(), version);
        }

        @Override
        public @NotNull ListIterator<E> listIterator()
        {
            return new TrackedIterator<>(inner.listIterator(), version);
        }

        @Override
        public @NotNull ListIterator<E> listIterator(final int index)
        {
            return new TrackedIterator<>(inner.listIterator(index), version);
        }

        @Override
        public void forEach(final Consumer<? super E> action)
        {
            inner.forEach(action);
        }

        @Override
        public Object[] toArray()
        {
            return inner.toArray();
        }

        @Override
        public String toString()
        {
            return inner.toString();
        }
    }

    // ------------------------------------------------------------------ linked list

    /**
     * The queues of the courier and crafter jobs are handed out as {@link LinkedList}s, so this is one.
     */
    private static final class TrackedLinkedList<E> extends LinkedList<E> implements Wrapper
    {
        private final StoreVersion version;

        private TrackedLinkedList(final StoreVersion version)
        {
            this.version = version;
        }

        @Override
        public StoreVersion version()
        {
            return version;
        }

        @Override
        public boolean add(final E e)
        {
            super.add(e);
            version.bump();
            return true;
        }

        @Override
        public void add(final int index, final E element)
        {
            super.add(index, element);
            version.bump();
        }

        @Override
        public boolean addAll(final @NotNull Collection<? extends E> c)
        {
            final boolean changed = super.addAll(c);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public boolean addAll(final int index, final @NotNull Collection<? extends E> c)
        {
            final boolean changed = super.addAll(index, c);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public void addFirst(final E e)
        {
            super.addFirst(e);
            version.bump();
        }

        @Override
        public void addLast(final E e)
        {
            super.addLast(e);
            version.bump();
        }

        @Override
        public boolean offer(final E e)
        {
            super.offer(e);
            version.bump();
            return true;
        }

        @Override
        public boolean offerFirst(final E e)
        {
            super.offerFirst(e);
            version.bump();
            return true;
        }

        @Override
        public boolean offerLast(final E e)
        {
            super.offerLast(e);
            version.bump();
            return true;
        }

        @Override
        public void push(final E e)
        {
            super.push(e);
            version.bump();
        }

        @Override
        public E remove()
        {
            final E e = super.remove();
            version.bump();
            return e;
        }

        @Override
        public E remove(final int index)
        {
            final E e = super.remove(index);
            version.bump();
            return e;
        }

        @Override
        public boolean remove(final Object o)
        {
            final boolean changed = super.remove(o);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public E removeFirst()
        {
            final E e = super.removeFirst();
            version.bump();
            return e;
        }

        @Override
        public E removeLast()
        {
            final E e = super.removeLast();
            version.bump();
            return e;
        }

        @Override
        public boolean removeFirstOccurrence(final Object o)
        {
            final boolean changed = super.removeFirstOccurrence(o);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public boolean removeLastOccurrence(final Object o)
        {
            final boolean changed = super.removeLastOccurrence(o);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public E poll()
        {
            final boolean had = !isEmpty();
            final E e = super.poll();
            if (had)
            {
                version.bump();
            }
            return e;
        }

        @Override
        public E pollFirst()
        {
            final boolean had = !isEmpty();
            final E e = super.pollFirst();
            if (had)
            {
                version.bump();
            }
            return e;
        }

        @Override
        public E pollLast()
        {
            final boolean had = !isEmpty();
            final E e = super.pollLast();
            if (had)
            {
                version.bump();
            }
            return e;
        }

        @Override
        public E pop()
        {
            final E e = super.pop();
            version.bump();
            return e;
        }

        @Override
        public E set(final int index, final E element)
        {
            final E old = super.set(index, element);
            version.bump();
            return old;
        }

        @Override
        public void clear()
        {
            final boolean had = !isEmpty();
            super.clear();
            if (had)
            {
                version.bump();
            }
        }

        @Override
        public boolean removeAll(final @NotNull Collection<?> c)
        {
            final boolean changed = super.removeAll(c);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public boolean retainAll(final @NotNull Collection<?> c)
        {
            final boolean changed = super.retainAll(c);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public boolean removeIf(final @NotNull Predicate<? super E> filter)
        {
            final boolean changed = super.removeIf(filter);
            if (changed)
            {
                version.bump();
            }
            return changed;
        }

        @Override
        public void replaceAll(final UnaryOperator<E> operator)
        {
            super.replaceAll(operator);
            version.bump();
        }

        @Override
        public void sort(final Comparator<? super E> c)
        {
            super.sort(c);
            version.bump();
        }

        @Override
        public @NotNull ListIterator<E> listIterator(final int index)
        {
            return new TrackedIterator<>(super.listIterator(index), version);
        }

        @Override
        public @NotNull Iterator<E> descendingIterator()
        {
            return new TrackedPlainIterator<>(super.descendingIterator(), version);
        }

        @Override
        public Object clone()
        {
            // A copy is a plain list of its own: it must not count into the store it was copied from.
            return new LinkedList<>(this);
        }
    }

    // ------------------------------------------------------------------ map

    /**
     * A map over any map (a hash map, a fastutil map), counting every change, and wrapping collection values.
     */
    private static final class TrackedMap<K, V> extends AbstractMap<K, V> implements Wrapper
    {
        private final Map<K, V>       inner;
        private final StoreVersion    version;
        @Nullable
        private final UnaryOperator<V> wrapValue;
        private Set<Entry<K, V>>      entries;

        private TrackedMap(final Map<K, V> inner, final StoreVersion version, @Nullable final UnaryOperator<V> wrapValue)
        {
            this.inner = inner;
            this.version = version;
            this.wrapValue = wrapValue;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static <V> UnaryOperator<V> collectionWrapper(final StoreVersion version)
        {
            return value -> (V) wrap((Collection) value, version);
        }

        @Override
        public StoreVersion version()
        {
            return version;
        }

        private V wrapped(final V value)
        {
            return wrapValue == null ? value : wrapValue.apply(value);
        }

        @Override
        public V put(final K key, final V value)
        {
            final V old = inner.put(key, wrapped(value));
            version.bump();
            return old;
        }

        @Override
        public void putAll(final @NotNull Map<? extends K, ? extends V> m)
        {
            for (final Entry<? extends K, ? extends V> e : m.entrySet())
            {
                put(e.getKey(), e.getValue());
            }
        }

        @Override
        public V remove(final Object key)
        {
            final V old = inner.remove(key);
            if (old != null)
            {
                version.bump();
            }
            return old;
        }

        @Override
        public boolean remove(final Object key, final Object value)
        {
            final boolean removed = inner.remove(key, value);
            if (removed)
            {
                version.bump();
            }
            return removed;
        }

        @Override
        public void clear()
        {
            if (!inner.isEmpty())
            {
                inner.clear();
                version.bump();
            }
        }

        @Override
        public V putIfAbsent(final K key, final V value)
        {
            final V existing = inner.putIfAbsent(key, wrapped(value));
            if (existing == null)
            {
                version.bump();
            }
            return existing;
        }

        @Override
        public V computeIfAbsent(final K key, final @NotNull Function<? super K, ? extends V> mappingFunction)
        {
            final boolean[] created = {false};
            final V result = inner.computeIfAbsent(key, k -> {
                final V made = mappingFunction.apply(k);
                if (made == null)
                {
                    return null;
                }
                created[0] = true;
                return wrapped(made);
            });
            if (created[0])
            {
                version.bump();
            }
            return result;
        }

        @Override
        public V computeIfPresent(final K key, final @NotNull BiFunction<? super K, ? super V, ? extends V> remappingFunction)
        {
            final boolean had = inner.get(key) != null;
            final V result = inner.computeIfPresent(key, (k, old) -> {
                final V made = remappingFunction.apply(k, old);
                return made == null ? null : wrapped(made);
            });
            if (had)
            {
                version.bump();
            }
            return result;
        }

        @Override
        public V compute(final K key, final @NotNull BiFunction<? super K, ? super V, ? extends V> remappingFunction)
        {
            final V result = inner.compute(key, (k, old) -> {
                final V made = remappingFunction.apply(k, old);
                return made == null ? null : wrapped(made);
            });
            version.bump();
            return result;
        }

        @Override
        public V merge(final K key, final @NotNull V value, final @NotNull BiFunction<? super V, ? super V, ? extends V> remappingFunction)
        {
            final V result = inner.merge(key, wrapped(value), (old, added) -> {
                final V made = remappingFunction.apply(old, added);
                return made == null ? null : wrapped(made);
            });
            version.bump();
            return result;
        }

        @Override
        public V replace(final K key, final V value)
        {
            final V old = inner.replace(key, wrapped(value));
            if (old != null)
            {
                version.bump();
            }
            return old;
        }

        @Override
        public boolean replace(final K key, final V oldValue, final V newValue)
        {
            final boolean replaced = inner.replace(key, oldValue, wrapped(newValue));
            if (replaced)
            {
                version.bump();
            }
            return replaced;
        }

        @Override
        public void replaceAll(final BiFunction<? super K, ? super V, ? extends V> function)
        {
            inner.replaceAll((k, v) -> wrapped(function.apply(k, v)));
            version.bump();
        }

        @Override
        public V get(final Object key)
        {
            return inner.get(key);
        }

        @Override
        public V getOrDefault(final Object key, final V defaultValue)
        {
            return inner.getOrDefault(key, defaultValue);
        }

        @Override
        public boolean containsKey(final Object key)
        {
            return inner.containsKey(key);
        }

        @Override
        public boolean containsValue(final Object value)
        {
            return inner.containsValue(value);
        }

        @Override
        public int size()
        {
            return inner.size();
        }

        @Override
        public boolean isEmpty()
        {
            return inner.isEmpty();
        }

        @Override
        public void forEach(final BiConsumer<? super K, ? super V> action)
        {
            inner.forEach(action);
        }

        @Override
        public @NotNull Set<Entry<K, V>> entrySet()
        {
            if (entries == null)
            {
                entries = new EntryView();
            }
            return entries;
        }

        private final class EntryView extends AbstractSet<Entry<K, V>>
        {
            @Override
            public @NotNull Iterator<Entry<K, V>> iterator()
            {
                final Iterator<Entry<K, V>> it = inner.entrySet().iterator();
                return new Iterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return it.hasNext();
                    }

                    @Override
                    public Entry<K, V> next()
                    {
                        final Entry<K, V> entry = it.next();
                        return new Entry<>()
                        {
                            @Override
                            public K getKey()
                            {
                                return entry.getKey();
                            }

                            @Override
                            public V getValue()
                            {
                                return entry.getValue();
                            }

                            @Override
                            public V setValue(final V value)
                            {
                                final V old = entry.setValue(wrapped(value));
                                version.bump();
                                return old;
                            }

                            @Override
                            public boolean equals(final Object o)
                            {
                                return o instanceof final Entry<?, ?> other && Objects.equals(getKey(), other.getKey()) && Objects.equals(getValue(), other.getValue());
                            }

                            @Override
                            public int hashCode()
                            {
                                return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
                            }

                            @Override
                            public String toString()
                            {
                                return getKey() + "=" + getValue();
                            }
                        };
                    }

                    @Override
                    public void remove()
                    {
                        it.remove();
                        version.bump();
                    }
                };
            }

            @Override
            public int size()
            {
                return inner.size();
            }

            @Override
            public void clear()
            {
                TrackedMap.this.clear();
            }

            @Override
            public boolean contains(final Object o)
            {
                return o instanceof final Entry<?, ?> e && inner.containsKey(e.getKey()) && Objects.equals(inner.get(e.getKey()), e.getValue());
            }

            @Override
            public boolean remove(final Object o)
            {
                if (o instanceof final Entry<?, ?> e && contains(o))
                {
                    TrackedMap.this.remove(e.getKey());
                    return true;
                }
                return false;
            }
        }
    }
}
