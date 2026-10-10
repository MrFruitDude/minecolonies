package com.minecolonies.core.colony.requestsystem.data;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.data.IRequestResolverRequestAssignmentDataStore;
import com.minecolonies.api.colony.requestsystem.factory.FactoryVoidInput;
import com.minecolonies.api.colony.requestsystem.factory.IFactory;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.NBTUtils;
import com.minecolonies.api.util.constant.NbtTagConstants;
import com.minecolonies.api.util.constant.SerializationIdentifierConstants;
import com.minecolonies.api.util.constant.TypeConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.ldtteam.structurize.api.util.Tuple;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class StandardRequestResolverRequestAssignmentDataStore implements IRequestResolverRequestAssignmentDataStore
{

    /**
     * The resolver to requests assignments. Mutations through the map or through its collections keep the index below
     * up to date.
     */
    private final IndexedAssignments assignments = new IndexedAssignments();

    /**
     * Which resolver holds a request. Answers {@link #getAssignmentForValue(IToken)} without looking at every resolver.
     */
    private final Map<IToken<?>, IToken<?>> resolverByRequest = new HashMap<>();

    /**
     * Whether a request was ever held by two resolvers at once. Normally a request has one resolver, and removing it
     * clears its index entry. With a duplicate the remaining holder has to be searched for.
     */
    private boolean sharedRequests;

    private IToken<?> id;

    public StandardRequestResolverRequestAssignmentDataStore(
      final IToken<?> id,
      final Map<IToken<?>, Collection<IToken<?>>> assignments
    )
    {
        this.id = id;
        this.assignments.putAll(assignments);
    }

    public StandardRequestResolverRequestAssignmentDataStore()
    {
        this(StandardFactoryController.getInstance().getNewInstance(TypeConstants.ITOKEN), new HashMap<>());
    }

    @NotNull
    @Override
    public Map<IToken<?>, Collection<IToken<?>>> getAssignments()
    {
        return assignments;
    }

    @Nullable
    @Override
    public IToken<?> getAssignmentForValue(final IToken<?> value)
    {
        return resolverByRequest.get(value);
    }

    @Override
    public IToken<?> getId()
    {
        return id;
    }

    @Override
    public void setId(final IToken<?> id)
    {
        this.id = id;
    }

    private void indexed(final IToken<?> request, final IToken<?> resolver)
    {
        final IToken<?> previous = resolverByRequest.put(request, resolver);
        if (previous != null && !previous.equals(resolver))
        {
            sharedRequests = true;
        }
    }

    private void unindexed(final IToken<?> request, final IToken<?> resolver)
    {
        if (!resolver.equals(resolverByRequest.get(request)))
        {
            return;
        }

        resolverByRequest.remove(request);
        if (sharedRequests)
        {
            for (final Map.Entry<IToken<?>, Collection<IToken<?>>> other : assignments.entrySet())
            {
                if (other.getValue().contains(request))
                {
                    resolverByRequest.put(request, other.getKey());
                    return;
                }
            }
        }
    }

    /**
     * The requests of one resolver: a set, so membership is a hash lookup, which reports every change to the index.
     */
    private final class AssignedRequests extends AbstractCollection<IToken<?>>
    {
        private final IToken<?>      resolver;
        private final Set<IToken<?>> requests = new LinkedHashSet<>();

        private AssignedRequests(final IToken<?> resolver)
        {
            this.resolver = resolver;
        }

        @Override
        public boolean add(final IToken<?> request)
        {
            if (!requests.add(request))
            {
                return false;
            }
            indexed(request, resolver);
            return true;
        }

        @Override
        public boolean remove(final Object request)
        {
            if (!requests.remove(request))
            {
                return false;
            }
            unindexed((IToken<?>) request, resolver);
            return true;
        }

        @Override
        public boolean contains(final Object request)
        {
            return requests.contains(request);
        }

        @Override
        public void clear()
        {
            for (final IToken<?> request : new ArrayList<>(requests))
            {
                remove(request);
            }
        }

        @Override
        public int size()
        {
            return requests.size();
        }

        @Override
        public Iterator<IToken<?>> iterator()
        {
            final Iterator<IToken<?>> inner = requests.iterator();
            return new Iterator<>()
            {
                private IToken<?> last;

                @Override
                public boolean hasNext()
                {
                    return inner.hasNext();
                }

                @Override
                public IToken<?> next()
                {
                    last = inner.next();
                    return last;
                }

                @Override
                public void remove()
                {
                    inner.remove();
                    unindexed(last, resolver);
                }
            };
        }
    }

    /**
     * The assignment map. Collections put into it are taken over as {@link AssignedRequests}; the map is changed through
     * put, putAll, remove and clear only.
     */
    private final class IndexedAssignments extends HashMap<IToken<?>, Collection<IToken<?>>>
    {
        @Override
        public Collection<IToken<?>> put(final IToken<?> resolver, final Collection<IToken<?>> requests)
        {
            final AssignedRequests taken = new AssignedRequests(resolver);
            final Collection<IToken<?>> previous = super.put(resolver, taken);
            if (previous != null)
            {
                unassign(resolver, previous);
            }
            taken.addAll(requests);
            return previous;
        }

        @Override
        public void putAll(final Map<? extends IToken<?>, ? extends Collection<IToken<?>>> map)
        {
            map.forEach(this::put);
        }

        @Override
        public Collection<IToken<?>> remove(final Object resolver)
        {
            final Collection<IToken<?>> previous = super.remove(resolver);
            if (previous != null)
            {
                unassign((IToken<?>) resolver, previous);
            }
            return previous;
        }

        @Override
        public void clear()
        {
            super.clear();
            resolverByRequest.clear();
            sharedRequests = false;
        }

        private void unassign(final IToken<?> resolver, final Collection<IToken<?>> requests)
        {
            for (final IToken<?> request : requests)
            {
                unindexed(request, resolver);
            }
        }

        @Override
        public Set<IToken<?>> keySet()
        {
            return Collections.unmodifiableSet(super.keySet());
        }

        @Override
        public Collection<Collection<IToken<?>>> values()
        {
            return Collections.unmodifiableCollection(super.values());
        }

        @Override
        public Set<Map.Entry<IToken<?>, Collection<IToken<?>>>> entrySet()
        {
            return Collections.unmodifiableSet(super.entrySet());
        }

        @Override
        public Collection<IToken<?>> putIfAbsent(final IToken<?> key, final Collection<IToken<?>> value)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public Collection<IToken<?>> computeIfAbsent(final IToken<?> key, final Function<? super IToken<?>, ? extends Collection<IToken<?>>> mappingFunction)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public Collection<IToken<?>> computeIfPresent(
          final IToken<?> key,
          final BiFunction<? super IToken<?>, ? super Collection<IToken<?>>, ? extends Collection<IToken<?>>> remappingFunction)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public Collection<IToken<?>> compute(
          final IToken<?> key,
          final BiFunction<? super IToken<?>, ? super Collection<IToken<?>>, ? extends Collection<IToken<?>>> remappingFunction)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public Collection<IToken<?>> merge(
          final IToken<?> key,
          final Collection<IToken<?>> value,
          final BiFunction<? super Collection<IToken<?>>, ? super Collection<IToken<?>>, ? extends Collection<IToken<?>>> remappingFunction)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public void replaceAll(final BiFunction<? super IToken<?>, ? super Collection<IToken<?>>, ? extends Collection<IToken<?>>> function)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public boolean remove(final Object key, final Object value)
        {
            throw new UnsupportedOperationException("use remove");
        }

        @Override
        public Collection<IToken<?>> replace(final IToken<?> key, final Collection<IToken<?>> value)
        {
            throw new UnsupportedOperationException("use put");
        }

        @Override
        public boolean replace(final IToken<?> key, final Collection<IToken<?>> oldValue, final Collection<IToken<?>> newValue)
        {
            throw new UnsupportedOperationException("use put");
        }
    }

    public static class Factory implements IFactory<FactoryVoidInput, StandardRequestResolverRequestAssignmentDataStore>
    {

        @NotNull
        @Override
        public TypeToken<? extends StandardRequestResolverRequestAssignmentDataStore> getFactoryOutputType()
        {
            return TypeToken.of(StandardRequestResolverRequestAssignmentDataStore.class);
        }

        @NotNull
        @Override
        public TypeToken<? extends FactoryVoidInput> getFactoryInputType()
        {
            return TypeConstants.FACTORYVOIDINPUT;
        }

        @NotNull
        @Override
        public StandardRequestResolverRequestAssignmentDataStore getNewInstance(
          @NotNull final IFactoryController factoryController, @NotNull final FactoryVoidInput factoryVoidInput, @NotNull final Object... context) throws IllegalArgumentException
        {
            return new StandardRequestResolverRequestAssignmentDataStore();
        }

        @NotNull
        @Override
        public CompoundTag serialize(
          @NotNull final HolderLookup.Provider provider,
          @NotNull final IFactoryController controller, @NotNull final StandardRequestResolverRequestAssignmentDataStore standardProviderRequestResolverAssignmentDataStore)
        {
            final CompoundTag compound = new CompoundTag();

            compound.put(NbtTagConstants.TAG_TOKEN, controller.serializeTag(provider, standardProviderRequestResolverAssignmentDataStore.id));
            compound.put(NbtTagConstants.TAG_LIST, standardProviderRequestResolverAssignmentDataStore.assignments.keySet().stream().map(t -> {
                final CompoundTag entryCompound = new CompoundTag();

                entryCompound.put(NbtTagConstants.TAG_TOKEN, controller.serializeTag(provider, t));
                entryCompound.put(NbtTagConstants.TAG_LIST, standardProviderRequestResolverAssignmentDataStore.assignments.get(t).stream()
                                                              .map(s -> StandardFactoryController.getInstance().serializeTag(provider, s))
                                                              .collect(NBTUtils.toListNBT()));

                return entryCompound;
            }).collect(NBTUtils.toListNBT()));

            return compound;
        }

        @NotNull
        @Override
        public StandardRequestResolverRequestAssignmentDataStore deserialize(@NotNull final HolderLookup.Provider provider, @NotNull final IFactoryController controller, @NotNull final CompoundTag nbt) throws Throwable
        {
            final IToken<?> token = controller.deserializeTag(provider, nbt.getCompoundOrEmpty(NbtTagConstants.TAG_TOKEN));
            final Map<IToken<?>, Collection<IToken<?>>> map = NBTUtils.streamCompound(nbt.getListOrEmpty(NbtTagConstants.TAG_LIST))
                                                                .map(CompoundTag -> {
                                                                    final IToken<?> elementToken = controller.deserializeTag(provider, CompoundTag.getCompoundOrEmpty(NbtTagConstants.TAG_TOKEN));
                                                                    final Collection<IToken<?>> elements = NBTUtils.streamCompound(CompoundTag.getListOrEmpty(NbtTagConstants.TAG_LIST)).map(elementCompound -> (IToken<?>) controller.deserializeTag(provider, elementCompound))
                                                                                                             .collect(Collectors.toList());

                                                                    return new Tuple<>(elementToken, elements);
                                                                }).collect(Collectors.toMap(t -> t.getA(), t -> t.getB()));

            return new StandardRequestResolverRequestAssignmentDataStore(token, map);
        }

        @Override
        public void serialize(
          IFactoryController controller, StandardRequestResolverRequestAssignmentDataStore input,
          RegistryFriendlyByteBuf packetBuffer)
        {
            controller.serialize(packetBuffer, input.id);
            packetBuffer.writeInt(input.assignments.size());
            input.assignments.forEach((key, value) -> {
                controller.serialize(packetBuffer, key);
                packetBuffer.writeInt(value.size());
                value.forEach(token -> controller.serialize(packetBuffer, token));
            });
        }

        @Override
        public StandardRequestResolverRequestAssignmentDataStore deserialize(
          IFactoryController controller,
          RegistryFriendlyByteBuf buffer) throws Throwable
        {
            final IToken<?> token = controller.deserialize(buffer);
            final Map<IToken<?>, Collection<IToken<?>>> assignments = new HashMap<>();
            final int assignmentsSize = buffer.readInt();
            for (int i = 0; i < assignmentsSize; ++i)
            {
                final IToken<?> key = controller.deserialize(buffer);
                final List<IToken<?>> tokens = new ArrayList<>();
                final int tokensSize = buffer.readInt();
                for (int ii = 0; ii < tokensSize; ++ii)
                {
                    tokens.add(controller.deserialize(buffer));
                }
                assignments.put(key, tokens);
            }

            return new StandardRequestResolverRequestAssignmentDataStore(token, assignments);
        }

        @Override
        public short getSerializationId()
        {
            return SerializationIdentifierConstants.STANDARD_REQUEST_RESOLVER_REQUEST_ASSIGNMENT_DATASTORE_ID;
        }
    }
}
