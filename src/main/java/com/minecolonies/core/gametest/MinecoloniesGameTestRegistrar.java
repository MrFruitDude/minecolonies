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
        ToolDurabilityGameTests.registerItems(modBus);
        modBus.addListener(new MinecoloniesGameTestRegistrar());
    }

    @Override
    public void accept(final RegisterGameTestsEvent event)
    {
        if (com.minecolonies.core.gametest.bench.BenchEnv.only())
        {
            // The request-system benchmark run: its tests only, so the rest of the suite does not share the JVM.
            com.minecolonies.core.gametest.bench.BenchRegistrar.register(event);
            return;
        }
        final String onlyProperty = System.getProperty("minecolonies.gametest.only", "");
        if (onlyProperty.contains("scaffoldbench"))
        {
            // the tall-structure benchmarks, three times each (the ticks vary a little from run to run)
            registerScaffoldBench(event);
            return;
        }
        if (!onlyProperty.isEmpty() && (onlyProperty.contains("worker_eff") || onlyProperty.contains("scaffold")))
        {
            if (onlyProperty.contains("worker_eff"))
            {
                registerWorkerEfficiency(event);
            }
            if (onlyProperty.contains("scaffold"))
            {
                registerBuilderScaffolding(event);
            }
            return;
        }
        final String only = System.getenv("MINECOLONIES_GAMETEST_ONLY");
        if (only != null && !only.isBlank())
        {
            // A development run of one group of tests: the rest of the suite does not share the JVM.
            if (only.contains("storage"))
            {
                StorageNetworkGameTests.register(event);
            }
            if (only.contains("reqsync"))
            {
                RequestSyncGameTests.register(event);
            }
            if (only.contains("courier"))
            {
                RequestCourierGameTests.register(event);
            }
            if (only.contains("sticks"))
            {
                registerScaffoldRecipe(event);
            }
            if (only.contains("idlehelper"))
            {
                registerIdleHelpers(event);
            }
            if (only.contains("tooldur"))
            {
                ToolDurabilityGameTests.register(event);
            }
            if (only.contains("labour"))
            {
                registerLabour(event);
            }
            if (only.contains("directed"))
            {
                registerDirected(event);
            }
            if (only.contains("expanded"))
            {
                registerExpanded(event);
            }
            if (only.contains("natural"))
            {
                registerNatural(event);
            }
            if (only.contains("waveb"))
            {
                registerWaveB(event);
            }
            if (only.contains("emptybp"))
            {
                EmptyBlueprintGameTests.register(event);
            }
            if (only.contains("pace"))
            {
                registerPace(event);
            }
            if (only.contains("path"))
            {
                registerPathfinding(event);
            }
            if (only.contains("workereff"))
            {
                // the worker efficiency tests (also -Dminecolonies.gametest.only=worker_eff), with the other groups in one JVM
                registerWorkerEfficiency(event);
            }
            if (only.contains("scaffold"))
            {
                // the builder reach and scaffolding tests (also -Dminecolonies.gametest.only=scaffold), with the other groups in one JVM
                registerBuilderScaffolding(event);
            }
            return;
        }
        StorageNetworkGameTests.register(event);
        final Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "default"));
        final TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
          environment,
          Identifier.fromNamespaceAndPath("minecraft", "empty"),
          40100,
          0,
          true,
          Rotation.NONE);

        // A run of one group in one GameTest JVM: -Dminecolonies.gametest.only=leisure registers only the leisure hook tests.
        if ("leisure".equals(System.getProperty("minecolonies.gametest.only")))
        {
            registerLeisure(event);
            return;
        }

        // (-Dminecolonies.gametest.only=crafterstats: the crafter statistics test alone)
        if ("crafterstats".equals(System.getProperty("minecolonies.gametest.only")))
        {
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, "crafter_records_inputs_as_used"),
              info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::crafterRecordsItsInputsAsUsed),
              isolatedColonyData(event, "crafter_records_inputs_as_used"));
            return;
        }

        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony_lifecycle"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::colonyLifecycle),
          isolatedColonyData(event, "colony_lifecycle"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "buildtool_townhall_paste"),
          info -> new MinecoloniesGameTestInstance(info, BuildToolGameTests::buildToolTownHallPaste),
          isolatedColonyData(event, "buildtool_townhall_paste"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "survival_player_actions"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::survivalPlayerActions),
          isolatedColonyData(event, "survival_player_actions"));
        for (final String name : new String[] {"warehouse_index_request_scaling", "warehouse_index_invalidation", "warehouse_index_dump_same_rack",
          "warehouse_ca14_one_rack_walk", "warehouse_ca14_followup_fresh", "warehouse_ca14_warehouses_only"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "warehouse_index_request_scaling" -> WarehouseIndexGameTests::warehouseIndexRequestScaling;
                case "warehouse_index_invalidation" -> WarehouseIndexGameTests::warehouseIndexInvalidation;
                case "warehouse_ca14_one_rack_walk" -> WarehouseIndexGameTests::warehouseCa14OneRackWalk;
                case "warehouse_ca14_followup_fresh" -> WarehouseIndexGameTests::warehouseCa14FollowupFresh;
                case "warehouse_ca14_warehouses_only" -> WarehouseIndexGameTests::warehouseCa14WarehousesOnly;
                default -> WarehouseIndexGameTests::warehouseIndexDumpSameRack;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        registerPathfinding(event);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rack_inventory_round_trip"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::rackInventoryRoundTrip),
          data);
        // NF48: ModEquipmentTypes.simulateBlockTransform on NeoForge .48's holder-based DataMapHooks transformer lookup.
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "equipment_simulate_block_transform"),
          info -> new MinecoloniesGameTestInstance(info, EquipmentTransformGameTests::simulateBlockTransform),
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
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "variant_eggs_feed_colony"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::variantEggsFeedColony),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "vanilla_spear_equipment"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::vanillaSpearEquipment),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "copper_equipment_levels"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::copperEquipmentLevels),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lumberjack_new_trees"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::lumberjackNewTrees),
          isolatedColonyData(event, "lumberjack_new_trees"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "guard_ignores_protected_creaking"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::guardIgnoresProtectedCreaking),
          isolatedColonyData(event, "guard_ignores_protected_creaking"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "florist_new_flowers"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::floristNewFlowers),
          // Its row of composted dirt is wider than the empty structure; the plot keeps neighbouring tests off it.
          plotData(environment));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "composter_new_plants"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::composterNewPlants),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "crop_biome_tags_new_biomes"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::cropBiomeTagsNewBiomes),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "copper_light_decorations"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::copperLightDecorations),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "stonemason_new_stone"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::stonemasonNewStone),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "domum_new_materials"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::domumNewMaterials),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "wool_concrete_stairs_fit"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::woolConcreteStairsFit),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "sawmill_learns_shelf"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::sawmillLearnsShelf),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "straw_bed_not_colony_bed"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::strawBedNotColonyBed),
          data);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "fletcher_saddle_harness"),
          info -> new MinecoloniesGameTestInstance(info, MinecoloniesGameTests::fletcherSaddleHarness),
          data);
        registerPace(event);
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "crafter_records_inputs_as_used"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::crafterRecordsItsInputsAsUsed),
          isolatedColonyData(event, "crafter_records_inputs_as_used"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "upgrade_request_event_cancels"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::upgradeRequestEventCancels),
          isolatedColonyData(event, "upgrade_request_event_cancels"));
        // P3a-MC: several colonies per player (one glob: minecolonies:multicolony_*).
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multicolony_founding_and_selection"),
          info -> new MinecoloniesGameTestInstance(info, MultiColonyGameTests::founding_and_selection),
          isolatedColonyData(event, "multicolony_founding_and_selection"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multicolony_default_limit_one"),
          info -> new MinecoloniesGameTestInstance(info, MultiColonyGameTests::default_limit_one),
          isolatedColonyData(event, "multicolony_default_limit_one"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multicolony_founding_event"),
          info -> new MinecoloniesGameTestInstance(info, MultiColonyGameTests::founding_event),
          isolatedColonyData(event, "multicolony_founding_event"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multicolony_abandon_delete_right_one"),
          info -> new MinecoloniesGameTestInstance(info, MultiColonyGameTests::abandon_delete_right_one),
          isolatedColonyData(event, "multicolony_abandon_delete_right_one"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multicolony_owned_colonies_message"),
          info -> new MinecoloniesGameTestInstance(info, MultiColonyGameTests::owned_colonies_message),
          isolatedColonyData(event, "multicolony_owned_colonies_message"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "multicolony_force_active_state"),
          info -> new MinecoloniesGameTestInstance(info, MultiColonyGameTests::force_active_state),
          isolatedColonyData(event, "multicolony_force_active_state"));
        // MP-D: a building's claim never takes another owner's chunks.
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "claims_other_owner_chunks_are_skipped"),
          info -> new MinecoloniesGameTestInstance(info, ClaimOwnerGameTests::building_claim_skips_other_owners_chunks),
          isolatedColonyData(event, "claims_other_owner_chunks_are_skipped"));
        // MULTIPLAYER (MP-A): two messages that let one player write to another's colony (one glob: minecolonies:mpsec_*).
        for (final String name : new String[] {"mpsec_trigger_connection_needs_both_colonies", "mpsec_create_colony_changes_the_hut_only_after_the_checks",
          "mpsec_create_colony_too_close_names_nobody", "mpsec_decoders_refuse_junk"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "mpsec_trigger_connection_needs_both_colonies" -> MpSecurityGameTests::trigger_connection_needs_both_colonies;
                case "mpsec_create_colony_changes_the_hut_only_after_the_checks" -> MpSecurityGameTests::create_colony_changes_the_hut_only_after_the_checks;
                case "mpsec_create_colony_too_close_names_nobody" -> MpSecurityGameTests::create_colony_too_close_names_nobody;
                default -> MpSecurityGameTests::decoders_refuse_junk;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        registerLeisure(event);
        // P5b-MC: NPC faction owner for AI neighbour colonies (one glob: minecolonies:faction_*).
        for (final String name : new String[] {"faction_create_and_permissions", "faction_persistence", "faction_index_and_limits", "faction_driver_state",
          "faction_actions_without_player", "faction_raids_off"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "faction_create_and_permissions" -> FactionColonyGameTests::create_and_permissions;
                case "faction_persistence" -> FactionColonyGameTests::persistence;
                case "faction_index_and_limits" -> FactionColonyGameTests::index_and_limits;
                case "faction_driver_state" -> FactionColonyGameTests::driver_state;
                case "faction_actions_without_player" -> FactionColonyGameTests::actions_without_player;
                default -> FactionColonyGameTests::raids_off;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        // CA-4 + CA-2 network sync (one glob: minecolonies:sync_*).
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "sync_view_message_no_tail"),
          info -> new MinecoloniesGameTestInstance(info, ColonySyncGameTests::viewMessageNoTail),
          isolatedColonyData(event, "sync_view_message_no_tail"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "sync_citizen_dirty_not_colony_dirty"),
          info -> new MinecoloniesGameTestInstance(info, ColonySyncGameTests::citizenDirtyNotColonyDirty),
          isolatedColonyData(event, "sync_citizen_dirty_not_colony_dirty"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "sync_citizen_derived_refresh"),
          info -> new MinecoloniesGameTestInstance(info, ColonySyncGameTests::citizenDerivedRefresh),
          isolatedColonyData(event, "sync_citizen_derived_refresh"));
        // CA-9: idle citizens skip travel. Each founds its own colony, so each gets its own isolated batch.
        for (final String name : new String[] {"idle_citizen_skips_move", "idle_citizen_push_moves", "idle_citizen_floor_removed_falls",
          "idle_citizen_hurt_knockback", "idle_citizen_path_walks", "idle_citizen_gravity_recheck", "idle_citizen_path_starts_at_once"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "idle_citizen_skips_move" -> IdleTravelGameTests::idleCitizenSkipsMove;
                case "idle_citizen_push_moves" -> IdleTravelGameTests::idleCitizenPushMoves;
                case "idle_citizen_floor_removed_falls" -> IdleTravelGameTests::idleCitizenFloorRemovedFalls;
                case "idle_citizen_hurt_knockback" -> IdleTravelGameTests::idleCitizenHurtKnockback;
                case "idle_citizen_path_walks" -> IdleTravelGameTests::idleCitizenPathWalks;
                case "idle_citizen_path_starts_at_once" -> IdleTravelGameTests::idleCitizenPathStartsAtOnce;
                default -> IdleTravelGameTests::idleCitizenGravityRecheck;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        // CA-5 building tick spread (one glob: minecolonies:colony_tick_spread*).
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony_tick_spread"),
          info -> new MinecoloniesGameTestInstance(info, ColonyTickSpreadGameTests::colonyTickSpread),
          isolatedColonyData(event, "colony_tick_spread"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony_tick_spread_min_stock"),
          info -> new MinecoloniesGameTestInstance(info, ColonyTickSpreadGameTests::colonyTickSpreadMinStock),
          isolatedColonyData(event, "colony_tick_spread_min_stock"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony_tick_spread_prestige"),
          info -> new MinecoloniesGameTestInstance(info, ColonyTickSpreadGameTests::colonyTickSpreadPrestige),
          isolatedColonyData(event, "colony_tick_spread_prestige"));
        // C0 builder work order fixes (one glob: minecolonies:builder_wo_*).
        for (final String name : new String[] {"builder_wo_unclaim_moves_progress", "builder_wo_invalid_removed_with_callbacks", "builder_wo_manual_claim",
          "builder_wo_priority", "builder_wo_zero_cost_progress", "builder_wo_materials_reused", "builder_wo_dead_fluids_state"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "builder_wo_unclaim_moves_progress" -> BuilderWorkOrderGameTests::unclaimMovesProgressWithOrder;
                case "builder_wo_invalid_removed_with_callbacks" -> BuilderWorkOrderGameTests::invalidOrderRemovedThroughOnRemoved;
                case "builder_wo_manual_claim" -> BuilderWorkOrderGameTests::manualClaimChecksLevelAndReleasesWithoutWorker;
                case "builder_wo_priority" -> BuilderWorkOrderGameTests::assignmentFollowsPriority;
                case "builder_wo_zero_cost_progress" -> BuilderWorkOrderGameTests::zeroCostOrderProgress;
                case "builder_wo_materials_reused" -> BuilderWorkOrderGameTests::reassignedOrderReusesMaterials;
                default -> BuilderWorkOrderGameTests::deadFluidsStateNotPersisted;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        // C1 builder collaboration (one glob: minecolonies:builder_collab_*).
        for (final String name : new String[] {"builder_collab_scheduler", "builder_collab_free_first", "builder_collab_limits", "builder_collab_level",
          "builder_collab_persist_sync", "builder_collab_two_builders", "builder_collab_hand_off", "builder_collab_reload", "builder_collab_off",
          "builder_collab_removal_reasons", "builder_collab_quiet_add", "builder_collab_project_affinity", "builder_collab_fast_clear",
          "builder_collab_assist_ledger", "builder_collab_rival_request"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "builder_collab_scheduler" -> BuilderCollabGameTests::schedulerHelpsThenHandsOff;
                case "builder_collab_free_first" -> BuilderCollabGameTests::schedulerFreeBuilderBeforeHelper;
                case "builder_collab_limits" -> BuilderCollabGameTests::schedulerLimitsHelpers;
                case "builder_collab_level" -> BuilderCollabGameTests::schedulerRespectsLevel;
                case "builder_collab_persist_sync" -> BuilderCollabGameTests::collabStatePersistsAndSyncs;
                case "builder_collab_two_builders" -> BuilderCollabGameTests::twoBuildersOneOrder;
                case "builder_collab_hand_off" -> BuilderCollabGameTests::newOrderTakesTheHelper;
                case "builder_collab_reload" -> BuilderCollabGameTests::reloadWhileHelping;
                case "builder_collab_removal_reasons" -> BuilderCollabGameTests::removalReasons;
                case "builder_collab_quiet_add" -> BuilderCollabGameTests::quietAdd;
                case "builder_collab_project_affinity" -> BuilderCollabGameTests::projectAffinity;
                case "builder_collab_fast_clear" -> BuilderCollabGameTests::fastClear;
                case "builder_collab_assist_ledger" -> BuilderCollabGameTests::assistClaimsOnTheLedger;
                case "builder_collab_rival_request" -> BuilderCollabGameTests::helperAndRivalRequestShareTheWarehouse;
                default -> BuilderCollabGameTests::collaborationOffChangesNothing;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        registerWorkerEfficiency(event);
        registerBuilderScaffolding(event);
        registerScaffoldRecipe(event);
        registerIdleHelpers(event);
        // C2 builder collaboration observability and controls (one glob: minecolonies:collab_obs_*).
        for (final String name : new String[] {"collab_obs_view", "collab_obs_cap", "collab_obs_pin", "collab_obs_pin_new", "collab_obs_refusals"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "collab_obs_view" -> CollabObservabilityGameTests::viewShowsLeadAssistantAndHandOff;
                case "collab_obs_cap" -> CollabObservabilityGameTests::controlsMaxAssistants;
                case "collab_obs_pin" -> CollabObservabilityGameTests::controlsPinSurvivesNewOrders;
                case "collab_obs_pin_new" -> CollabObservabilityGameTests::controlsPinBringsABuilder;
                default -> CollabObservabilityGameTests::controlsRefusals;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
        // CA-1 + CA-3 request-system / citizen view sync (one glob: minecolonies:reqsync_*).
        RequestSyncGameTests.register(event);
        RequestCourierGameTests.register(event);
        // Citizens' tools wear like a player's tools (one glob: minecolonies:tooldur_*).
        ToolDurabilityGameTests.register(event);
        // A blank blueprint path (source stashes) stays blank and breaks nothing (one glob: minecolonies:emptybp_*).
        EmptyBlueprintGameTests.register(event);
        // Request system defect checks (one glob: minecolonies:rs_*).
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_craft_batch_zero"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::craftRecipeTooBigForOneBatch),
          isolatedColonyData(event, "rs_craft_batch_zero"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_completed_once"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::completedRequestsAreListedOnce),
          isolatedColonyData(event, "rs_completed_once"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_recipe_tokens_seeded"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::recipeTokensAreNotReproducibleWhileTokensAreSeeded),
          isolatedColonyData(event, "rs_recipe_tokens_seeded"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_min_stock_remove"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::minimumStockRemovalCancelsItsRequest),
          isolatedColonyData(event, "rs_min_stock_remove"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_delivery_full"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::deliveryToFullBuildingIsNotResolved),
          isolatedColonyData(event, "rs_delivery_full"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_building_reserved"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::buildingResolverLeavesReservedItems),
          isolatedColonyData(event, "rs_building_reserved"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_null_safety"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::nullSafety),
          isolatedColonyData(event, "rs_null_safety"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_courier_scaling"),
          info -> new MinecoloniesGameTestInstance(info, RequestSystemGameTests::courierTaskSelectionScales),
          isolatedColonyData(event, "rs_courier_scaling"));
        // RS1: the reservation ledger.
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_ledger_algebra"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::ledgerAlgebra),
          isolatedColonyData(event, "rs_ledger_algebra"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_compete"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::competingRequests),
          isolatedColonyData(event, "rs_reserve_compete"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_compete_flag_off"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::competingRequestsFlagOff),
          isolatedColonyData(event, "rs_reserve_compete_flag_off"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_cancel"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::cancelReleases),
          isolatedColonyData(event, "rs_reserve_cancel"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_save_load"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::saveLoadKeepsReservations),
          isolatedColonyData(event, "rs_reserve_save_load"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_sweep"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::sweepDropsOrphans),
          isolatedColonyData(event, "rs_reserve_sweep"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_courier_commit"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::courierCommits),
          isolatedColonyData(event, "rs_reserve_courier_commit"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_reserve_building_handout"),
          info -> new MinecoloniesGameTestInstance(info, RequestReservationGameTests::buildingHandoutHeld),
          isolatedColonyData(event, "rs_reserve_building_handout"));
        // The acceptance test (own glob: minecolonies:rsx_*): a hut placed with zero materials gets built.
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rsx_headline_flags_off"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestSystemHeadlineGameTests.headline(helper, false)),
          longIsolatedColonyData(event, "rsx_headline_flags_off", 60_000));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rsx_headline_flags_on"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestSystemHeadlineGameTests.headline(helper, true)),
          longIsolatedColonyData(event, "rsx_headline_flags_on", 60_000));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rsx_headline_split_off"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestSystemHeadlineGameTests.headline(helper, false, true)),
          longIsolatedColonyData(event, "rsx_headline_split_off", 60_000));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rsx_headline_split_on"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestSystemHeadlineGameTests.headline(helper, true, true)),
          longIsolatedColonyData(event, "rsx_headline_split_on", 60_000));
        // RS2: named wait reasons, event-driven re-evaluation.
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_unproducible_player_once"),
          info -> new MinecoloniesGameTestInstance(info, RequestWaitGameTests::unproducibleGoesToPlayerOnce),
          isolatedColonyData(event, "rs_wait_unproducible_player_once"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_producible_quiet"),
          info -> new MinecoloniesGameTestInstance(info, RequestWaitGameTests::producibleWaitsQuietly),
          isolatedColonyData(event, "rs_wait_producible_quiet"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_state_events"),
          info -> new MinecoloniesGameTestInstance(info, RequestWaitGameTests::stateChangedEvents),
          isolatedColonyData(event, "rs_wait_state_events"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_reason_synced"),
          info -> new MinecoloniesGameTestInstance(info, RequestWaitGameTests::waitReasonIsSynced),
          isolatedColonyData(event, "rs_wait_reason_synced"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_ladder_on"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.unproducibleLadder(helper, true)),
          isolatedColonyData(event, "rs_wait_ladder_on"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_ladder_off"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.unproducibleLadder(helper, false)),
          longIsolatedColonyData(event, "rs_wait_ladder_off", 80_000));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_stock_wake_on"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.wakeOnStock(helper, true)),
          isolatedColonyData(event, "rs_wait_stock_wake_on"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_stock_wake_off"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.wakeOnStock(helper, false)),
          isolatedColonyData(event, "rs_wait_stock_wake_off"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_partial_on"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.partialArrival(helper, true)),
          isolatedColonyData(event, "rs_wait_partial_on"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_partial_off"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.partialArrival(helper, false)),
          isolatedColonyData(event, "rs_wait_partial_off"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_crafter_hired_on"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.crafterHiredLate(helper, true)),
          isolatedColonyData(event, "rs_wait_crafter_hired_on"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_crafter_hired_off"),
          info -> new MinecoloniesGameTestInstance(info, helper -> RequestWaitGameTests.crafterHiredLate(helper, false)),
          isolatedColonyData(event, "rs_wait_crafter_hired_off"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_wait_courier_stuck"),
          info -> new MinecoloniesGameTestInstance(info, RequestWaitGameTests::stuckCourierGivesUp),
          isolatedColonyData(event, "rs_wait_courier_stuck"));
        // RS3: priority classes.
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_far_nine"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::farNineBeforeNearFive),
          isolatedColonyData(event, "rs_prio_far_nine"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_equal"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::equalClassesOldestFirst),
          isolatedColonyData(event, "rs_prio_equal"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_alarm"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::alarmBeatsNine),
          isolatedColonyData(event, "rs_prio_alarm"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_alarm_expired"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::expiredAlarmIsIgnored),
          isolatedColonyData(event, "rs_prio_alarm_expired"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_urgent_background"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::urgentBeforeBackground),
          isolatedColonyData(event, "rs_prio_urgent_background"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_derivation"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::importanceDerivation),
          isolatedColonyData(event, "rs_prio_derivation"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_flag_off"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::flagOffKeepsNearerFirst),
          isolatedColonyData(event, "rs_prio_flag_off"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_flag_off_importance"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::flagOffIgnoresImportance),
          isolatedColonyData(event, "rs_prio_flag_off_importance"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_save_load"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::saveLoadKeepsPriorities),
          isolatedColonyData(event, "rs_prio_save_load"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_prio_old_save"),
          info -> new MinecoloniesGameTestInstance(info, RequestPriorityGameTests::oldSaveLoadsWithDefaults),
          isolatedColonyData(event, "rs_prio_old_save"));
    }

    /**
     * The pathfinding tests (CA-10 / CA-11 and round 10). Part of the full suite; run on their own with
     * {@code MINECOLONIES_GAMETEST_ONLY=path}.
     */
    private void registerPathfinding(final RegisterGameTestsEvent event)
    {
        // CA-10 / CA-11 pathfinding: each in its own batch (own environment), so no other test's path jobs share the
        // worker pool or the submit observer while one is measured.
        for (final String name : new String[] {"path_unreachable_backoff", "path_unreachable_other_target", "path_unreachable_recovers",
          "path_queue_reject_marks_failed", "path_queue_parallel", "path_routes_identical", "path_citizen_walks_home", "path_road_preference",
          "path_unreachable_colony_cache", "path_failed_search_cap", "path_queue_fair_colonies",
          "path_route_hints_followed", "path_route_hints_fallback", "path_colony_cache_boxed_citizen", "path_long_detour_reaches",
          "path_road_heuristic_nodes", "path_colony_cache_walled_yard", "path_road_heuristic_citizen",
          "path_close_to_after_exact_proof"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "path_unreachable_backoff" -> PathfindingGameTests::unreachableTargetBackoff;
                case "path_unreachable_other_target" -> PathfindingGameTests::unreachableTargetOtherTarget;
                case "path_unreachable_recovers" -> PathfindingGameTests::unreachableTargetRecovers;
                case "path_queue_reject_marks_failed" -> PathfindingGameTests::pathQueueRejectMarksFailed;
                case "path_queue_parallel" -> PathfindingGameTests::pathQueueParallel;
                case "path_routes_identical" -> PathfindingGameTests::pathRoutesIdentical;
                case "path_road_preference" -> PathfindingGameTests::pathRoadPreference;
                case "path_unreachable_colony_cache" -> PathfindingGameTests::pathUnreachableColonyCache;
                case "path_failed_search_cap" -> PathfindingGameTests::pathFailedSearchCap;
                case "path_queue_fair_colonies" -> PathfindingGameTests::pathQueueFairColonies;
                case "path_route_hints_followed" -> PathfindingGameTests::pathRouteHintsFollowed;
                case "path_route_hints_fallback" -> PathfindingGameTests::pathRouteHintsFallback;
                case "path_colony_cache_boxed_citizen" -> PathfindingGameTests::pathColonyCacheBoxedCitizen;
                case "path_long_detour_reaches" -> PathfindingGameTests::pathLongDetourReaches;
                case "path_road_heuristic_nodes" -> PathfindingGameTests::pathRoadHeuristicNodes;
                case "path_colony_cache_walled_yard" -> PathfindingGameTests::pathColonyCacheWalledYard;
                case "path_road_heuristic_citizen" -> PathfindingGameTests::pathRoadHeuristicCitizen;
                case "path_close_to_after_exact_proof" -> PathfindingGameTests::pathCloseToAfterExactProof;
                default -> PathfindingGameTests::pathCitizenWalksHome;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
    }

    /**
     * L3 worker efficiency tests. Run on their own with {@code JAVA_TOOL_OPTIONS=-Dminecolonies.gametest.only=worker_eff}.
     */
    private void registerWorkerEfficiency(final RegisterGameTestsEvent event)
    {
        for (final String name : new String[] {"worker_eff_bench_place_l1", "worker_eff_bench_place_l40", "worker_eff_bench_clear_l1", "worker_eff_bench_clear_l40",
          "worker_eff_stuck_shaft", "worker_eff_stuck_no_tool", "worker_eff_stuck_unloaded", "worker_eff_stuck_floating",
          "worker_eff_set_bench_place_l40_plain", "worker_eff_set_bench_place_l1_plain", "worker_eff_set_bench_clear_l40_plain", "worker_eff_set_bench_place_l40_max",
          "worker_eff_set_abstract_walking", "worker_eff_set_walk_speed"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "worker_eff_bench_place_l1" -> WorkerEfficiencyGameTests::benchPlaceL1;
                case "worker_eff_bench_place_l40" -> WorkerEfficiencyGameTests::benchPlaceL40;
                case "worker_eff_bench_clear_l1" -> WorkerEfficiencyGameTests::benchClearL1;
                case "worker_eff_bench_clear_l40" -> WorkerEfficiencyGameTests::benchClearL40;
                case "worker_eff_stuck_shaft" -> WorkerEfficiencyGameTests::stuckInShaft;
                case "worker_eff_stuck_no_tool" -> WorkerEfficiencyGameTests::noToolForStone;
                case "worker_eff_stuck_unloaded" -> WorkerEfficiencyGameTests::unloadedTargetKeepsCitizen;
                case "worker_eff_stuck_floating" -> WorkerEfficiencyGameTests::floatingBlockDoesNotHang;
                case "worker_eff_set_bench_place_l40_plain" -> WorkerSettingsGameTests::benchPlaceL40Plain;
                case "worker_eff_set_bench_place_l1_plain" -> WorkerSettingsGameTests::benchPlaceL1Plain;
                case "worker_eff_set_bench_clear_l40_plain" -> WorkerSettingsGameTests::benchClearL40Plain;
                case "worker_eff_set_bench_place_l40_max" -> WorkerSettingsGameTests::benchPlaceL40Max;
                case "worker_eff_set_abstract_walking" -> WorkerSettingsGameTests::abstractWalkingRelocates;
                default -> WorkerSettingsGameTests::walkSpeedCalibration;
            };
            if (!nameWanted(name))
            {
                continue;
            }
            // (a development run can repeat a test: -Dminecolonies.gametest.reps=N, the repeats are told apart by a suffix)
            final int reps = Math.max(1, Integer.getInteger("minecolonies.gametest.reps", 1));
            for (int rep = 1; rep <= reps; rep++)
            {
                final String id = reps == 1 ? name : name + "_r" + rep;
                event.registerTest(
                  Identifier.fromNamespaceAndPath(Constants.MOD_ID, id),
                  info -> new MinecoloniesGameTestInstance(info, test),
                  isolatedColonyData(event, id));
            }
        }
    }

    /**
     * A development filter inside a group: {@code -Dminecolonies.gametest.names=a,b,!c} keeps the tests whose name contains one of the words and none of the words with a ! in front.
     */
    private static boolean nameWanted(final String name)
    {
        final String names = System.getProperty("minecolonies.gametest.names", "");
        if (names.isBlank())
        {
            return true;
        }
        boolean wanted = false;
        for (final String word : names.split(","))
        {
            final String w = word.trim();
            if (w.startsWith("!"))
            {
                if (name.contains(w.substring(1)))
                {
                    return false;
                }
            }
            else if (!w.isEmpty() && name.contains(w))
            {
                wanted = true;
            }
        }
        return wanted;
    }

    private void registerScaffoldBench(final RegisterGameTestsEvent event)
    {
        final int reps = Integer.getInteger("minecolonies.gametest.reps", 3);
        for (int i = 1; i <= reps; i++)
        {
            for (final String base : new String[] {"tall8", "tall14", "overhang", "sealed", "tall8_legacy", "tall14_legacy", "overhang_legacy", "sealed_legacy", "clear", "clear_legacy", "two", "two_solo", "twobig", "twobig_solo", "onebig", "onebig_legacy", "bridge", "wall", "wall_solo", "tower16", "tower16_solo", "mid", "mid_solo"})
            {
                final String name = "scaffoldbench_" + base + "_" + i;
                if (!nameWanted(name))
                {
                    continue;
                }
                final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (base)
                {
                    case "tall8" -> BuilderScaffoldGameTests::tallWall8;
                    case "tall14" -> BuilderScaffoldGameTests::tallTower14;
                    case "overhang" -> BuilderScaffoldGameTests::overhangSlab;
                    case "sealed" -> BuilderScaffoldGameTests::sealedRoof;
                    case "tall8_legacy" -> BuilderScaffoldGameTests::tallWall8Legacy;
                    case "tall14_legacy" -> BuilderScaffoldGameTests::tallTower14Legacy;
                    case "overhang_legacy" -> BuilderScaffoldGameTests::overhangLegacy;
                    case "clear" -> BuilderScaffoldGameTests::reachClearL40;
                    case "clear_legacy" -> BuilderScaffoldGameTests::legacyClearL40;
                    case "onebig" -> BuilderScaffoldGameTests::oneBuilderBig;
                    case "onebig_legacy" -> BuilderScaffoldGameTests::oneBuilderBigLegacy;
                    case "two" -> BuilderScaffoldGameTests::twoBuilders;
                    case "twobig" -> BuilderScaffoldGameTests::twoBuildersBig;
                    case "two_solo" -> BuilderScaffoldGameTests::twoBuildersSolo;
                    case "twobig_solo" -> BuilderScaffoldGameTests::twoBuildersBigSolo;
                    case "bridge" -> BuilderScaffoldGameTests::bridgeOverTrench;
                    case "wall" -> BuilderScaffoldGameTests::twoBuildersWall;
                    case "wall_solo" -> BuilderScaffoldGameTests::twoBuildersWallSolo;
                    case "mid" -> BuilderScaffoldGameTests::twoBuildersMid;
                    case "mid_solo" -> BuilderScaffoldGameTests::twoBuildersMidSolo;
                    case "tower16" -> BuilderScaffoldGameTests::twoBuildersTower16;
                    case "tower16_solo" -> BuilderScaffoldGameTests::twoBuildersTower16Solo;
                    default -> BuilderScaffoldGameTests::sealedRoofLegacy;
                };
                event.registerTest(
                  Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
                  info -> new MinecoloniesGameTestInstance(info, test),
                  isolatedColonyData(event, name));
            }
        }
    }

    /**
     * Scaffolding from sticks: the crafting table recipe and the sawmill's. Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=sticks}.
     */
    private void registerScaffoldRecipe(final RegisterGameTestsEvent event)
    {
        for (final String name : new String[] {"sticks_scaffold_table", "sticks_scaffold_sawmill_knows", "sticks_scaffold_sawmill_chain"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "sticks_scaffold_table" -> ScaffoldRecipeGameTests::tableRecipe;
                case "sticks_scaffold_sawmill_knows" -> ScaffoldRecipeGameTests::sawmillKnowsIt;
                default -> ScaffoldRecipeGameTests::sawmillCraftsRequestedScaffolding;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
    }

    /**
     * Unemployed citizens help builders. Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=idlehelper}.
     */
    private void registerIdleHelpers(final RegisterGameTestsEvent event)
    {
        for (final String name : new String[] {"idle_helper_faster", "idle_helper_no_order", "idle_helper_unled_order", "idle_helper_job", "idle_helper_night", "idle_helper_needs",
          "idle_helper_setting", "idle_helper_cap", "idle_helper_colonies", "idle_helper_arbiter_api", "idle_helper_breaks", "idle_helper_reload", "idle_helper_tool_wear"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "idle_helper_faster" -> IdleHelperGameTests::helperJoinsAndBuildIsFaster;
                case "idle_helper_no_order" -> IdleHelperGameTests::noOrderNothingHappens;
                case "idle_helper_unled_order" -> IdleHelperGameTests::unledOrderIsNotClaimed;
                case "idle_helper_job" -> IdleHelperGameTests::helperLeavesWhenGivenAJob;
                case "idle_helper_night" -> IdleHelperGameTests::helperLeavesAtNight;
                case "idle_helper_needs" -> IdleHelperGameTests::helperLeavesForNeeds;
                case "idle_helper_setting" -> IdleHelperGameTests::settingOffSendsHelpersAway;
                case "idle_helper_cap" -> IdleHelperGameTests::perBuilderCap;
                case "idle_helper_colonies" -> IdleHelperGameTests::coloniesAreIsolated;
                case "idle_helper_breaks" -> IdleHelperGameTests::helperBreaksAndPlaces;
                case "idle_helper_reload" -> IdleHelperGameTests::helperStateSavedAndRestartHandled;
                case "idle_helper_tool_wear" -> IdleHelperGameTests::helperWearsTheLeadsTool;
                default -> IdleHelperGameTests::arbiterOffersTasksByPriority;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              name.equals("idle_helper_faster") ? longIsolatedColonyData(event, name, 60_000) : isolatedColonyData(event, name));
        }
    }

    /**
     * The expanded basic tier: wood stairs, stripped logs, the Architect's Cutter's exact variants, a directed stone order's pickaxe. Run on their own with
     * {@code MINECOLONIES_GAMETEST_ONLY=expanded}.
     */
    private void registerExpanded(final RegisterGameTestsEvent event)
    {
        for (final String name : ExpandedLabourGameTests.NAMES)
        {
            if (skipped(name))
            {
                continue;
            }
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = ExpandedLabourGameTests.test(name);
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              longIsolatedColonyData(event, name, 20_000));
        }
    }

    /**
     * @return whether a development run leaves the test out ({@code MINECOLONIES_GAMETEST_SKIP}: test names separated by commas; for a run of only the cases a change
     *   touches).
     */
    private static boolean skipped(final String name)
    {
        final String skip = System.getenv("MINECOLONIES_GAMETEST_SKIP");
        return skip != null && java.util.Arrays.asList(skip.split(",")).contains(name);
    }

    /**
     * Labourers take natural resources only. Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=natural}.
     */
    private void registerNatural(final RegisterGameTestsEvent event)
    {
        for (final String name : NaturalSafetyGameTests.NAMES)
        {
            if (skipped(name))
            {
                continue;
            }
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = NaturalSafetyGameTests.test(name);
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              longIsolatedColonyData(event, name, 20_000));
        }
    }

    /**
     * The colony pace provider hooks (pace_*): the provider scales AI delays and action cycles, composes with worker speed, and the default changes nothing.
     * Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=pace}; the full suite registers them too.
     */
    private void registerPace(final RegisterGameTestsEvent event)
    {
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pace_provider_scales_ai_delay"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::paceProviderScalesAiDelay),
          isolatedColonyData(event, "pace_provider_scales_ai_delay"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pace_provider_scales_action_cycle"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::paceProviderScalesActionCycle),
          isolatedColonyData(event, "pace_provider_scales_action_cycle"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pace_provider_composes_with_worker_speed"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::paceProviderComposesWithWorkerSpeed),
          isolatedColonyData(event, "pace_provider_composes_with_worker_speed"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pace_default_provider_unchanged"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::paceDefaultProviderUnchanged),
          isolatedColonyData(event, "pace_default_provider_unchanged"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pace_real_food_drain"),
          info -> new MinecoloniesGameTestInstance(info, ColonyHooksGameTests::paceRealFoodDrainUsesFoodModifier),
          longIsolatedColonyData(event, "pace_real_food_drain", 12000));
    }

    /**
     * Wave B of the early loop: quarry zones, glass, the early pace and a hut on credit. Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=waveb}.
     */
    private void registerWaveB(final RegisterGameTestsEvent event)
    {
        for (final String name : WaveBLabourGameTests.NAMES)
        {
            if (skipped(name))
            {
                continue;
            }
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = WaveBLabourGameTests.test(name);
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              longIsolatedColonyData(event, name, 20_000));
        }
    }

    /**
     * Directed gathering orders: a manager sends chosen jobless citizens to gather in an area. Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=directed}.
     */
    private void registerDirected(final RegisterGameTestsEvent event)
    {
        for (final String name : DirectedLabourGameTests.NAMES)
        {
            if (skipped(name))
            {
                continue;
            }
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = DirectedLabourGameTests.test(name);
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              longIsolatedColonyData(event, name, 20_000));
        }
    }

    /**
     * The labourers: unemployed citizens gather and craft what no building serves. Run on their own with {@code MINECOLONIES_GAMETEST_ONLY=labour}.
     */
    private void registerLabour(final RegisterGameTestsEvent event)
    {
        for (final String name : new String[] {"labour_crafts_wooden_tools", "labour_covered_left_alone", "labour_stops_on_job", "labour_setting_off", "labour_colonies_isolated",
          "labour_builder_self_supply", "labour_stone_tools", "labour_withdraw_covered", "labour_stockpile", "labour_charcoal", "labour_furnace_taken", "labour_natural_trees",
          "labour_coverage", "labour_ledger_sweep", "labour_caps", "labour_retrying_held", "labour_night", "labour_builder_self_supply_retry",
          "labour_builder_self_supply_order_gone", "labour_builder_self_supply_tool", "labour_builder_self_supply_no_bamboo",
          "labour_builder_self_supply_needs"})
        {
            if (skipped(name))
            {
                continue;
            }
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "labour_crafts_wooden_tools" -> LabourGameTests::craftsWoodenTools;
                case "labour_covered_left_alone" -> LabourGameTests::coveredIsLeftAlone;
                case "labour_stops_on_job" -> LabourGameTests::stopsWhenGivenAJob;
                case "labour_setting_off" -> LabourGameTests::settingOff;
                case "labour_builder_self_supply" -> LabourGameTests::builderFetchesOwnBasics;
                case "labour_stone_tools" -> LabourGameTests::stoneToolsNeedAWoodenPickaxe;
                case "labour_withdraw_covered" -> LabourGameTests::coveredGoodsComeFromTheWarehouseOnly;
                case "labour_stockpile" -> LabourGameTests::stockpileFillsAndStops;
                case "labour_charcoal" -> LabourGameTests::charcoalAtAFurnace;
                case "labour_furnace_taken" -> LabourGameTests::furnaceWithThingsInItIsLeftAlone;
                case "labour_natural_trees" -> LabourGameTests::naturalTreesOnly;
                case "labour_coverage" -> LabourGameTests::coverageRules;
                case "labour_ledger_sweep" -> LabourGameTests::ledgerSweepsOrphanedLabourClaims;
                case "labour_caps" -> LabourGameTests::capsAndExclusiveClaims;
                case "labour_retrying_held" -> LabourGameTests::retryingHeldRequestIsServed;
                case "labour_night" -> LabourGameTests::nightEndsTheWork;
                case "labour_builder_self_supply_retry" -> LabourGameTests::builderTriesAgainAfterEmptyEpisodes;
                case "labour_builder_self_supply_order_gone" -> LabourGameTests::builderLetsGoWhenTheOrderEnds;
                case "labour_builder_self_supply_tool" -> LabourGameTests::builderMakesHisOwnTool;
                case "labour_builder_self_supply_no_bamboo" -> LabourGameTests::builderMakesNoScaffoldingFromNothing;
                case "labour_builder_self_supply_needs" -> LabourGameTests::builderNeedsDoNotCountAgainstTheRequest;
                default -> LabourGameTests::coloniesAreIsolated;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              longIsolatedColonyData(event, name, 20_000));
        }
    }

    /**
     * L4 builder reach and scaffolding tests. Run on their own with {@code JAVA_TOOL_OPTIONS=-Dminecolonies.gametest.only=scaffold}.
     */
    private void registerBuilderScaffolding(final RegisterGameTestsEvent event)
    {
        for (final String name : new String[] {"scaffold_reach_box_l40", "scaffold_reach_box_l1", "scaffold_reach_clear_l40", "scaffold_legacy_box_l40", "scaffold_legacy_box_l1",
          "scaffold_tall_wall8", "scaffold_tall_tower14", "scaffold_sealed_roof", "scaffold_overhang", "scaffold_tall_tower14_legacy", "scaffold_tall_wall8_legacy",
          "scaffold_cancel", "scaffold_save_load", "scaffold_orphans", "scaffold_two_builders", "scaffold_no_items", "scaffold_lava", "scaffold_held", "scaffold_bridge", "scaffold_overhang_legacy", "scaffold_sealed_legacy", "scaffold_two_builders_big", "scaffold_escape_pit", "scaffold_supply_delivery", "scaffold_supply_crafters", "scaffold_clear_ledge", "scaffold_climb_arm_twice", "scaffold_probe_arm", "scaffold_probe_descend", "scaffold_probe_wall5", "scaffold_probe_top1", "scaffold_probe_top3", "scaffold_probe_top8", "scaffold_probe_inside5", "scaffold_stock_arrives", "scaffold_sealed_no_items",
          "scaffold_lamp_top", "scaffold_flooded_cell", "scaffold_tall_no_scaffold", "scaffold_slow_delivery",
          "scaffold_pillar_undelivered", "scaffold_flooded_pool", "scaffold_tall_hut_no_scaffold",
          "scaffold_path_blocked", "scaffold_default_no_place_while_pathing", "scaffold_path_blocked_lava",
          "scaffold_path_blocked_13th", "scaffold_path_blocked_clear"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "scaffold_reach_box_l40" -> BuilderScaffoldGameTests::reachBoxL40;
                case "scaffold_reach_box_l1" -> BuilderScaffoldGameTests::reachBoxL1;
                case "scaffold_reach_clear_l40" -> BuilderScaffoldGameTests::reachClearL40;
                case "scaffold_legacy_box_l40" -> BuilderScaffoldGameTests::legacyBoxL40;
                case "scaffold_legacy_box_l1" -> BuilderScaffoldGameTests::legacyBoxL1;
                case "scaffold_tall_wall8" -> BuilderScaffoldGameTests::tallWall8;
                case "scaffold_tall_tower14" -> BuilderScaffoldGameTests::tallTower14;
                case "scaffold_sealed_roof" -> BuilderScaffoldGameTests::sealedRoof;
                case "scaffold_sealed_no_items" -> BuilderScaffoldGameTests::sealedRoofNoItems;
                case "scaffold_overhang" -> BuilderScaffoldGameTests::overhangSlab;
                case "scaffold_tall_tower14_legacy" -> BuilderScaffoldGameTests::tallTower14Legacy;
                case "scaffold_tall_wall8_legacy" -> BuilderScaffoldGameTests::tallWall8Legacy;
                case "scaffold_cancel" -> BuilderScaffoldGameTests::cancelMidBuild;
                case "scaffold_save_load" -> BuilderScaffoldGameTests::saveLoadMidScaffold;
                case "scaffold_orphans" -> BuilderScaffoldGameTests::orphansAfterLoad;
                case "scaffold_two_builders" -> BuilderScaffoldGameTests::twoBuilders;
                case "scaffold_no_items" -> BuilderScaffoldGameTests::noScaffoldingItems;
                case "scaffold_lava" -> BuilderScaffoldGameTests::impossibleOverLava;
                case "scaffold_held" -> BuilderScaffoldGameTests::positionUnderScaffolding;
                case "scaffold_bridge" -> BuilderScaffoldGameTests::bridgeOverTrench;
                case "scaffold_overhang_legacy" -> BuilderScaffoldGameTests::overhangLegacy;
                case "scaffold_sealed_legacy" -> BuilderScaffoldGameTests::sealedRoofLegacy;
                case "scaffold_two_builders_big" -> BuilderScaffoldGameTests::twoBuildersBig;
                case "scaffold_escape_pit" -> BuilderScaffoldGameTests::escapeFromPit;
                case "scaffold_supply_delivery" -> ScaffoldSupplyGameTests::warehouseDelivers;
                case "scaffold_supply_crafters" -> ScaffoldSupplyGameTests::craftersThatKnowScaffolding;
                case "scaffold_clear_ledge" -> BuilderScaffoldGameTests::clearNextToALedge;
                case "scaffold_climb_arm_twice" -> BuilderScaffoldGameTests::climbArmTwice;
                case "scaffold_probe_arm" -> BuilderScaffoldGameTests::armProbe;
                case "scaffold_probe_descend" -> BuilderScaffoldGameTests::descendProbe;
                case "scaffold_probe_wall5" -> BuilderScaffoldGameTests::climbWall5;
                case "scaffold_probe_top1" -> BuilderScaffoldGameTests::climbTop1;
                case "scaffold_probe_top3" -> BuilderScaffoldGameTests::climbTop3;
                case "scaffold_probe_top8" -> BuilderScaffoldGameTests::climbTop8;
                case "scaffold_probe_inside5" -> BuilderScaffoldGameTests::climbInside5;
                case "scaffold_lamp_top" -> BuilderScaffoldGameTests::lampTopFromAStandSpot;
                case "scaffold_flooded_cell" -> BuilderScaffoldGameTests::floodedCellIsFilled;
                case "scaffold_tall_no_scaffold" -> BuilderScaffoldGameTests::tallBlockWithoutScaffolding;
                case "scaffold_slow_delivery" -> BuilderScaffoldGameTests::slowDeliveryNoFallback;
                case "scaffold_pillar_undelivered" -> BuilderScaffoldGameTests::pillarScaffoldingNeverDelivered;
                case "scaffold_flooded_pool" -> BuilderScaffoldGameTests::floodedPoolFallback;
                case "scaffold_tall_hut_no_scaffold" -> BuilderScaffoldGameTests::tallHutWithoutScaffolding;
                case "scaffold_path_blocked" -> BuilderScaffoldGameTests::pathBlockedOrderEnds;
                case "scaffold_path_blocked_lava" -> BuilderScaffoldGameTests::pathBlockedOverLava;
                case "scaffold_path_blocked_13th" -> BuilderScaffoldGameTests::pathBlocked13thReturn;
                case "scaffold_path_blocked_clear" -> BuilderScaffoldGameTests::pathBlockedClear;
                case "scaffold_default_no_place_while_pathing" -> BuilderScaffoldGameTests::noDefaultPlacementWhilePathing;
                default -> BuilderScaffoldGameTests::stockArrives;
            };
            if (!nameWanted(name))
            {
                continue;
            }
            // MINECOLONIES_GAMETEST_REPEAT=n registers each test n times as name_r1..name_rn, each in its own environment: the copies run one after another.
            // (--repeatCount runs its copies side by side, and these scenes share static counters and test switches: r9 c3 launch 1.)
            final int repeat = repeatCount();
            for (int r = 1; r <= repeat; r++)
            {
                final String id = repeat > 1 ? name + "_r" + r : name;
                event.registerTest(
                  Identifier.fromNamespaceAndPath(Constants.MOD_ID, id),
                  info -> new MinecoloniesGameTestInstance(info, test),
                  isolatedColonyData(event, id));
            }
        }
    }

    private static int repeatCount()
    {
        try
        {
            final String value = System.getenv("MINECOLONIES_GAMETEST_REPEAT");
            return value == null || value.isBlank() ? 1 : Math.max(1, Integer.parseInt(value.trim()));
        }
        catch (final NumberFormatException e)
        {
            return 1;
        }
    }

    /**
     * LOGISTICS-AND-WORKERS B3: the citizen leisure hook (one glob: minecolonies:leisure_*).
     */
    private void registerLeisure(final RegisterGameTestsEvent event)
    {
        for (final String name : new String[] {"leisure_default_provider_keeps_the_site", "leisure_provider_replaces_vetoes_and_cannot_break_it", "leisure_wander_asks_the_provider"})
        {
            final java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> test = switch (name)
            {
                case "leisure_default_provider_keeps_the_site" -> LeisureGameTests::default_provider_keeps_the_site;
                case "leisure_provider_replaces_vetoes_and_cannot_break_it" -> LeisureGameTests::provider_replaces_vetoes_and_cannot_break_it;
                default -> LeisureGameTests::wander_asks_the_provider;
            };
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, test),
              isolatedColonyData(event, name));
        }
    }

    /**
     * Test data for a test that founds a colony: its own environment (so its own batch) that deletes every colony
     * before and after it runs and starts at sunrise with clear weather, on a plot big enough that no other test's cell overlaps its fixture.
     */
    public static TestData<Holder<TestEnvironmentDefinition<?>>> isolatedColonyData(final RegisterGameTestsEvent event, final String name)
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
        return plotData(environment, 40100);
    }

    private static TestData<Holder<TestEnvironmentDefinition<?>>> plotData(final Holder<TestEnvironmentDefinition<?>> environment, final int maxTicks)
    {
        return new TestData<>(environment, COLONY_PLOT, maxTicks, 0, true, Rotation.NONE);
    }

    /**
     * Like {@link #isolatedColonyData} for a test that runs for a long time (the headline acceptance test).
     */
    public static TestData<Holder<TestEnvironmentDefinition<?>>> longIsolatedColonyData(final RegisterGameTestsEvent event, final String name, final int maxTicks)
    {
        return plotData(event.registerEnvironment(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony/" + name),
          new ColonyIsolationEnvironment(name),
          new TestEnvironmentDefinition.Weather(TestEnvironmentDefinition.Weather.Type.CLEAR)), maxTicks);
    }
}
