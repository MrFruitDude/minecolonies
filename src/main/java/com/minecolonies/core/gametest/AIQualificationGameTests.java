package com.minecolonies.core.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Existing intent and server-side security cases, selected without launching the entire mod suite. */
final class AIQualificationGameTests
{
    static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    static
    {
        TESTS.put("life_statuses", Round13LifeGameTests::statuses);
        TESTS.put("life_sick_home", Round13LifeGameTests::sickHome);
        TESTS.put("scaffold_watched_policy", BuilderScaffoldGameTests::watchedPolicy);
        TESTS.put("multicolony_founding_and_selection", MultiColonyGameTests::founding_and_selection);
        TESTS.put("multicolony_default_limit_one", MultiColonyGameTests::default_limit_one);
        TESTS.put("multicolony_founding_event", MultiColonyGameTests::founding_event);
        TESTS.put("multicolony_abandon_delete_right_one", MultiColonyGameTests::abandon_delete_right_one);
        TESTS.put("multicolony_owned_colonies_message", MultiColonyGameTests::owned_colonies_message);
        TESTS.put("multicolony_force_active_state", MultiColonyGameTests::force_active_state);
        TESTS.put("mpsec_trigger_connection_needs_both_colonies", MpSecurityGameTests::trigger_connection_needs_both_colonies);
        TESTS.put("mpsec_create_colony_changes_the_hut_only_after_the_checks", MpSecurityGameTests::create_colony_changes_the_hut_only_after_the_checks);
        TESTS.put("mpsec_create_colony_too_close_names_nobody", MpSecurityGameTests::create_colony_too_close_names_nobody);
        TESTS.put("mpsec_decoders_refuse_junk", MpSecurityGameTests::decoders_refuse_junk);
        TESTS.put("claims_other_owner_chunks_are_skipped", ClaimOwnerGameTests::building_claim_skips_other_owners_chunks);
    }

    static void register(final RegisterGameTestsEvent event)
    {
        TESTS.forEach((name, test) -> event.registerTest(Identifier.fromNamespaceAndPath("minecolonies", name),
          info -> new MinecoloniesGameTestInstance(info, test), MinecoloniesGameTestRegistrar.isolatedColonyData(event, name)));
    }
}
