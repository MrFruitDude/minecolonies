package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IVisitorData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ITavernVisitorModule;
import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.core.colony.buildings.modules.TavernBuildingModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Free towns phase 2, the fork side (glob {@code minecolonies:freetowns_*}, group {@code MINECOLONIES_GAMETEST_ONLY=freetowns}): the tavern's visitor
 * room ({@link ITavernVisitorModule}) and the room-checked, seeded visitor spawn that Colony Command's villager invite uses.
 */
public final class FreeTownsGameTests
{
    private FreeTownsGameTests()
    {
    }

    static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    static
    {
        TESTS.put("freetowns_tavern_room", FreeTownsGameTests::tavernRoom);
        TESTS.put("freetowns_seeded_visitor", FreeTownsGameTests::seededVisitor);
    }

    static void register(final RegisterGameTestsEvent event)
    {
        for (final Map.Entry<String, Consumer<GameTestHelper>> entry : TESTS.entrySet())
        {
            final Consumer<GameTestHelper> test = entry.getValue();
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, entry.getKey()),
              info -> new MinecoloniesGameTestInstance(info, test),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, entry.getKey()));
        }
    }

    private static TavernBuildingModule tavern(final GameTestHelper helper, final IColony colony)
    {
        final IBuilding building = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutTavern, new BlockPos(12, 1, 6),
          "fundamentals/tavern1.blueprint");
        final List<ITavernVisitorModule> modules = building.getModules(ITavernVisitorModule.class);
        helper.assertTrue(modules.size() == 1 && modules.get(0) instanceof TavernBuildingModule, "the tavern has one visitor module: " + modules);
        return (TavernBuildingModule) modules.get(0);
    }

    private static int visitors(final IColony colony)
    {
        return colony.getVisitorManager().getCivilianDataMap().size();
    }

    /**
     * Room is {@code 3 * level}: none at level 0, three at level 1. Below the cap the room-checked spawn adds exactly one visitor each time; at the cap it
     * refuses and changes nothing, while the unchecked {@code spawnVisitor()} keeps its old behaviour.
     */
    public static void tavernRoom(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "freetowns room");
        final TavernBuildingModule module = tavern(helper, colony);
        final IBuilding building = colony.getServerBuildingManager().getBuildings().values().stream()
          .filter(b -> b.getModules(ITavernVisitorModule.class).contains(module)).findFirst().orElseThrow();

        building.setBuildingLevel(0);
        helper.assertTrue(!module.hasVisitorRoom(), "an unbuilt tavern has no room");
        helper.assertTrue(ITavernVisitorModule.withRoom(colony) == null, "no tavern with room at level 0");
        helper.assertTrue(module.spawnVisitorIfRoom(ITavernVisitorModule.Seed.DEFAULT) == null && visitors(colony) == 0, "level 0 spawns nothing");

        building.setBuildingLevel(1);
        helper.assertTrue(module.hasVisitorRoom() && ITavernVisitorModule.withRoom(colony) == module, "a level 1 tavern has room");
        for (int i = 1; i <= 3; i++)
        {
            final int before = visitors(colony);
            final IVisitorData visitor = module.spawnVisitorIfRoom(ITavernVisitorModule.Seed.DEFAULT);
            helper.assertTrue(visitor != null, "visitor " + i + " below the cap was refused");
            helper.assertTrue(visitors(colony) == before + 1 && module.getExternalCitizens().size() == i && module.getExternalCitizens().contains(visitor.getId()),
              "spawn " + i + " adds exactly one visitor: " + visitors(colony) + " " + module.getExternalCitizens());
            helper.assertTrue(colony.getVisitorManager().getVisitor(visitor.getId()) == visitor && !visitor.getRecruitCost().isEmpty(), "the visitor is registered with a cost");
        }

        helper.assertTrue(!module.hasVisitorRoom(), "three visitors fill a level 1 tavern");
        helper.assertTrue(ITavernVisitorModule.withRoom(colony) == null, "no tavern with room at the cap");
        helper.assertTrue(module.spawnVisitorIfRoom(ITavernVisitorModule.Seed.DEFAULT) == null, "the room-checked spawn refuses at the cap");
        helper.assertTrue(visitors(colony) == 3 && module.getExternalCitizens().size() == 3, "a refused spawn changes nothing");

        // A visitor gone from the manager frees its slot for the room-checked spawn at once (not only on the next colony tick).
        final IVisitorData gone = colony.getVisitorManager().getVisitor(module.getExternalCitizens().get(0));
        colony.getVisitorManager().removeCivilian(gone);
        helper.assertTrue(module.spawnVisitorIfRoom(ITavernVisitorModule.Seed.DEFAULT) != null && module.getExternalCitizens().size() == 3 && visitors(colony) == 3,
          "a freed slot takes one new visitor: " + module.getExternalCitizens());
        helper.assertTrue(!module.hasVisitorRoom(), "full again");

        building.setBuildingLevel(2);
        helper.assertTrue(module.hasVisitorRoom(), "level 2 has room for six");
        helper.succeed();
    }

    /**
     * The seed's name, skills (held to 1..99), recruit tier (tier 9: netherite boots) and story line are applied; blank and over-long names are handled.
     */
    public static void seededVisitor(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "freetowns seed");
        final TavernBuildingModule module = tavern(helper, colony);
        final Component story = Component.literal("came from Oakbrook");
        final IVisitorData visitor = module.spawnVisitorIfRoom(
          new ITavernVisitorModule.Seed("  Alda Brook  ", Map.of(Skill.Strength, 7, Skill.Knowledge, 500, Skill.Mana, -3), 9, story));
        helper.assertTrue(visitor != null, "the seeded spawn was refused");

        helper.assertTrue("Alda Brook".equals(visitor.getName()), "name: " + visitor.getName());
        helper.assertTrue(visitor.getCitizenSkillHandler().getLevel(Skill.Strength) == 7, "strength " + visitor.getCitizenSkillHandler().getLevel(Skill.Strength));
        helper.assertTrue(visitor.getCitizenSkillHandler().getLevel(Skill.Knowledge) == 99, "knowledge held to 99");
        helper.assertTrue(visitor.getCitizenSkillHandler().getLevel(Skill.Mana) == 1, "mana held to 1");
        helper.assertTrue(!visitor.getRecruitCost().isEmpty(), "the visitor costs a recruit item");
        helper.assertTrue(visitor.getEntity().isPresent(), "the visitor entity spawned");
        helper.assertTrue(visitor.getEntity().get().getItemBySlot(EquipmentSlot.FEET).is(Items.NETHERITE_BOOTS), "tier 9 wears netherite boots");
        helper.assertTrue(visitor.getEntity().get().getCustomName() != null && "Alda Brook".equals(visitor.getEntity().get().getCustomName().getString()),
          "the entity carries the name: " + visitor.getEntity().get().getCustomName());

        final Map<Component, IInteractionResponseHandler> options = chatOptions(helper, visitor);
        helper.assertTrue(options.containsKey(story) && "came from Oakbrook".equals(options.get(story).getInquiry().getString()), "the story line is attached: " + options.keySet());

        final IVisitorData plain = module.spawnVisitorIfRoom(new ITavernVisitorModule.Seed("   ", null, 0, null));
        helper.assertTrue(plain != null && !plain.getName().isBlank(), "a blank name keeps the generated one: '" + (plain == null ? null : plain.getName()) + "'");
        helper.assertTrue(chatOptions(helper, plain).keySet().stream().anyMatch(k -> k.getString().length() > 0), "a default story line is attached");

        final IVisitorData longName = module.spawnVisitorIfRoom(new ITavernVisitorModule.Seed("x".repeat(200), Map.of(), 1, null));
        helper.assertTrue(longName != null && longName.getName().length() == ITavernVisitorModule.MAX_NAME_LENGTH, "a long name is cut");
        helper.assertTrue(longName.getEntity().isPresent() && longName.getEntity().get().getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS), "tier 1 wears leather boots");
        helper.assertTrue(module.spawnVisitorIfRoom(ITavernVisitorModule.Seed.DEFAULT) == null, "and the tavern is full");
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Map<Component, IInteractionResponseHandler> chatOptions(final GameTestHelper helper, final IVisitorData visitor)
    {
        try
        {
            final Field field = CitizenData.class.getDeclaredField("citizenChatOptions");
            field.setAccessible(true);
            return (Map<Component, IInteractionResponseHandler>) field.get(visitor);
        }
        catch (final ReflectiveOperationException e)
        {
            helper.fail("chat options unreadable: " + e);
            return Map.of();
        }
    }
}
