package com.minecolonies.core.gametest;

import com.minecolonies.api.util.constant.Constants;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * Registers MineColonies' MC 26.2 GameTest coverage when the dedicated GameTest run is enabled.
 */
public final class MinecoloniesGameTestRegistrar implements Consumer<RegisterGameTestsEvent>
{
    /**
     * The custom instance type must be in the test_instance_type registry: worlds with GameTests loaded sync every
     * test instance to joining clients, and encoding an unregistered type throws, leaving the client on the loading screen.
     */
    private static final DeferredRegister<MapCodec<? extends GameTestInstance>> INSTANCE_TYPES =
      DeferredRegister.create(Registries.TEST_INSTANCE_TYPE, Constants.MOD_ID);

    /**
     * Environment types are registry entries too, synced to joining clients like the instance types above.
     */
    private static final DeferredRegister<MapCodec<? extends TestEnvironmentDefinition<?>>> ENVIRONMENT_TYPES =
      DeferredRegister.create(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Constants.MOD_ID);

    /**
     * Empty structure big enough to hold the largest colony fixture (x -8..59, z -8..39 relative to its origin), so
     * the test grid spaces colony tests apart from each other and from every other test.
     */
    private static final Identifier COLONY_PLOT = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gametest/colony_plot");

    /**
     * The restart pair hands a saved world from one run to the next through this marker file; without it both tests
     * fail on tick 0, so they are only registered for the two-run restart procedure.
     */
    private static final String RESTART_MARKER_ENV = "MINECOLONIES_RESTART_MARKER";

    static
    {
        INSTANCE_TYPES.register("minecolonies_function", () -> MinecoloniesGameTestInstance.CODEC);
        ENVIRONMENT_TYPES.register("colony_isolation", () -> ColonyIsolationEnvironment.CODEC);
    }

    /**
     * Hooks the GameTest registrations onto the mod bus. Call during mod construction only.
     */
    public static void register(final IEventBus modBus)
    {
        INSTANCE_TYPES.register(modBus);
        ENVIRONMENT_TYPES.register(modBus);
        modBus.addListener(new MinecoloniesGameTestRegistrar());
    }

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
          isolatedColonyData(event, "colony_lifecycle"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "survival_player_actions"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::survivalPlayerActions),
          isolatedColonyData(event, "survival_player_actions"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rack_inventory_round_trip"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::rackInventoryRoundTrip),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "production_courier_builder"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::productionCourierBuilder),
          isolatedColonyData(event, "production_courier_builder"));
        if (System.getenv(RESTART_MARKER_ENV) != null)
        {
            // They run one at a time in the two-run procedure. Prepare builds the same 48-wide colony as
            // production_courier_builder, so it gets the same empty plot: the framework then loads every chunk the
            // fixture uses before the test starts, instead of the test racing chunk generation for its citizen
            // anchors. It must NOT use the colony-isolation environment, whose teardown deletes the colony the
            // resume run has to load.
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, "production_courier_builder_restart_prepare"),
              info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::productionCourierBuilderRestartPrepare),
              plotData(event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony/restart_prepare"),
                new TestEnvironmentDefinition.Weather(TestEnvironmentDefinition.Weather.Type.CLEAR))));
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, "production_courier_builder_restart_resume"),
              info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::productionCourierBuilderRestartResume),
              data);
        }
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multipiston_lifecycle"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::multiPistonLifecycle),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multipiston_packet_reach"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::multiPistonPacketReach),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multipiston_move_reports_problems"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::multiPistonMoveReportsProblems),
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
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "architects_cutter_input_clicks"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::architectsCutterInputClicks),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_texture_data_load"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumTextureDataLoad),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_material_tints"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumMaterialTints),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_block_properties"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumBlockProperties),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multipiston_recipe_and_loot"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::multipistonRecipeAndLoot),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "compostable_items"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::compostableItems),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "compat_sync_round_trip"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::compatSyncRoundTrip),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "data_listeners_build_stacks"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::dataListenersBuildStacks),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "compat_message_clientbound_only"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::compatMessageClientboundOnly),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "no_onlyin_annotations"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::noOnlyInAnnotations),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "server_classes_verify_without_client"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::serverClassesVerifyWithoutClient),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_pack_folder_in_jar"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizePackFolderInJar),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_extra_block_mineable_tags"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumExtraBlockMineableTags),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_placement_ghost"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumPlacementGhost),
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
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "fast_entity_fluid_throttle"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::fastEntityFluidThrottle),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "modded_tool_material_levels"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::moddedToolMaterialLevels),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lumberjack_scepter_left_click"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::lumberjackScepterLeftClick),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_pos_selector_left_click"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizePosSelectorLeftClick),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "citizen_inventory_menu_layout"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::citizenInventoryMenuLayout),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_safe_pack_name"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeSafePackName),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_falling_block_support"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeFallingBlockSupport),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_container_placement"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeContainerPlacement),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_item_extraction"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeItemExtraction),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_replace_applies_item_components"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeReplaceAppliesItemComponents),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_manager_drains_queue_per_tick"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeManagerDrainsQueuePerTick),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_pack_transfer_payload"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizePackTransferPayload),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_entity_placement_rules"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeEntityPlacementRules),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_tag_substitution_rotation"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeTagSubstitutionRotation),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_tag_substitution_legacy_formats"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeTagSubstitutionLegacyFormats),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "item_handler_wrap_null_capability"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::itemHandlerWrapNullCapability),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "item_handler_own_provider_direct"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::itemHandlerOwnProviderDirect),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "enchanter_books_have_enchantments"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::enchanterBooksHaveEnchantments),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "global_loot_modifiers_active"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::globalLootModifiersActive),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "content_parity_items"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::contentParityItems),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "locale_files_present"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::localeFilesPresent),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_shingle_item_names"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumShingleItemNames),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_cycled_material_cache"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumCycledMaterialCache),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "legacy_config_migration"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::legacyConfigMigration),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "block_models_no_item_geometry"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::blockModelsNoItemGeometry),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "world_type_checks"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::worldTypeChecks),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "blockui_vanilla_text_colors"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::blockuiVanillaTextColors),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "dynamictrees_worldgen_read_zone"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::dynamicTreesWorldgenReadZone),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_blueprint_bed_block_entities"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeBlueprintBedBlockEntities),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_blueprint_fake_level"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeBlueprintFakeLevel),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_server_uuid"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeServerUuid),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_scan_tool_teleport"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeScanToolTeleport),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_network_framing"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeNetworkFraming),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "vanilla_particle_message_round_trip"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::vanillaParticleMessageRoundTrip),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "client_recipe_watch"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::clientRecipeWatch),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "citizen_render_flags"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::citizenRenderFlags),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "item_nbt_matching_table"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::itemNbtMatchingTable),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "armor_equipment_assets"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::armorEquipmentAssets),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "client_recipe_sync"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::clientRecipeSync),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_cutter_recipe_sync"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumCutterRecipeSync),
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
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "structurize_dynamic_registry_items"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::structurizeDynamicRegistryItems),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gametest_instance_type_registered"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::gameTestInstanceTypeRegistered),
          data);
    }

    /**
     * Test data for a test that founds a colony: its own environment (so its own batch) that deletes every colony
     * before and after it runs and starts at sunrise with clear weather, on a plot big enough that no other test's cell overlaps its fixture.
     */
    private static TestData<Holder<TestEnvironmentDefinition<?>>> isolatedColonyData(final RegisterGameTestsEvent event, final String name)
    {
        return plotData(event.registerEnvironment(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony/" + name),
          new ColonyIsolationEnvironment(name),
          // A single run starts on a fresh world with clear skies; a full batch can reach a rain cycle, and citizens
          // stop working in the rain.
          new TestEnvironmentDefinition.Weather(TestEnvironmentDefinition.Weather.Type.CLEAR)));
    }

    private static TestData<Holder<TestEnvironmentDefinition<?>>> plotData(final Holder<TestEnvironmentDefinition<?>> environment)
    {
        return new TestData<>(environment, COLONY_PLOT, 40100, 0, true, Rotation.NONE);
    }
}
