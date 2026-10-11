package com.minecolonies.core.event;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.core.datalistener.*;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import com.minecolonies.core.util.BackUpHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.DefaultDataComponentsBoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Event handler used to catch various forge events.
 */
public class FMLEventHandler
{
    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Pre event)
    {
        IColonyManager.getInstance().onServerTick(event);
        DataPackSyncEventHandler.ServerEvents.load(event.getServer());
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Pre event)
    {
        IColonyManager.getInstance().onClientTick(event);
    }

    @SubscribeEvent
    public static void onPlayerLogin(@NotNull final PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof final ServerPlayer player)
        {
            // Looking up the owned colonies reloads the owner of a colony if it failed to load, and builds the owner index.
            // The client then learns which colonies the player owns, also the ones it is not subscribed to.
            IColonyManager.getInstance().getIColoniesByOwner(player.getUUID());
            IColonyManager.getInstance().syncOwnedColonies(player, true);
            //ColonyManager.syncAllColoniesAchievements();
        }
    }

    @SubscribeEvent
    public static void onAddServerReloadListenerEvent(@NotNull final AddServerReloadListenersEvent event)
    {
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "crafter_recipes"), new CrafterRecipeListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "research"), new ResearchListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "custom_visitors"), new CustomVisitorListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "citizen_names"), new CitizenNameListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "quests"), new QuestJsonListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "item_nbt"), new ItemNbtListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "study_items"), StudyItemListener.INSTANCE);
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "diseases"), new DiseasesListener());
        event.addListener(Identifier.fromNamespaceAndPath("minecolonies", "recruitment_items"), new RecruitmentItemsListener());
    }

    @SubscribeEvent
    public static void onDefaultComponentsBound(@NotNull final DefaultDataComponentsBoundEvent event)
    {
        DeferredDataApply.onComponentsBound(event);
    }

    @SubscribeEvent
    public static void onServerStarted(@NotNull final ServerStartedEvent event)
    {
        BackUpHelper.loadMissingColonies();
    }

    @SubscribeEvent
    public static void onWorldTick(final LevelTickEvent.Pre event)
    {
        IColonyManager.getInstance().onWorldTick(event);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(@NotNull final ServerAboutToStartEvent event)
    {
        IColonyManager.getInstance().getRecipeManager().reset();
    }

    @SubscribeEvent
    public static void onServerStopped(@NotNull final ServerStoppingEvent event)
    {
        Pathfinding.shutdown();
        DataPackSyncEventHandler.ServerEvents.reset();
    }
}
