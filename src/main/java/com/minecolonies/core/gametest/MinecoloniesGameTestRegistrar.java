package com.minecolonies.core.gametest;

import com.minecolonies.api.util.constant.Constants;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.function.Consumer;

/**
 * Registers MineColonies' MC 26.2 GameTest coverage when the dedicated GameTest run is enabled.
 */
public final class MinecoloniesGameTestRegistrar implements Consumer<RegisterGameTestsEvent>
{
    @Override
    public void accept(final RegisterGameTestsEvent event)
    {
        final Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "default"));
        final TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
          environment,
          Identifier.fromNamespaceAndPath("minecraft", "empty"),
          40100,
          0,
          true,
          Rotation.NONE);

        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony_lifecycle"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::colonyLifecycle),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "survival_player_actions"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::survivalPlayerActions),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rack_inventory_round_trip"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::rackInventoryRoundTrip),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "production_courier_builder"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::productionCourierBuilder),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "production_courier_builder_restart_prepare"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::productionCourierBuilderRestartPrepare),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "production_courier_builder_restart_resume"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::productionCourierBuilderRestartResume),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multipiston_lifecycle"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::multiPistonLifecycle),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multipiston_obstruction"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::multiPistonObstruction),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "architects_cutter_recipe_lookup"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::architectsCutterRecipeLookup),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "container_contents_drop_on_break"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::containerContentsDropOnBreak),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "citizen_minecart_cleanup"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::citizenMinecartCleanup),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lumberjack_scepter_left_click"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::lumberjackScepterLeftClick),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "survival_placement_handlers_registered"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::survivalPlacementHandlersRegistered),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_tool_data_persists"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeToolDataPersists),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "blueprint_data_block_entity_format"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::blueprintDataBlockEntityFormat),
          data);
    }
}
