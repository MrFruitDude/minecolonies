# Scaffolding from sticks, and idle citizens that help builders

Two gameplay changes of the `integ/anno` line (branch `anno/idle-helpers`).

## 1. Scaffolding from sticks alone

Builders place and request `minecraft:scaffolding` (`BuilderScaffolding`, the request is a plain `Stack` of the item through `hut.createRequest`). The vanilla recipe
wants 6 bamboo and a string for 6 pieces, which an early colony cannot make. Two recipes, both shipped by the fork:

| | where | inputs | output |
|---|---|---|---|
| crafting table | `data/minecolonies/recipe/scaffolding_from_sticks.json` (datagen: `DefaultRecipeProvider`) | 7 sticks, `SSS / S S / S S` | 4 scaffolding |
| sawmill (colony crafter) | `data/minecolonies/crafterrecipes/sawmill/scaffolding_from_sticks.json` (datagen: `DefaultSawmillCraftingProvider`) | 7 sticks | 4 scaffolding |

The vanilla recipe is untouched (the game test checks it still makes 6 from 6 bamboo and a string).

**Why 7 sticks for 4.** The cost is a deliberate premium over vanilla. Vanilla: 6 bamboo (3 sticks' worth) and a string for 6 pieces, about 0.5 sticks and a sixth of
a string per piece. Sticks only: 7 sticks for 4 pieces, 1.75 sticks per piece, 3.5 times the wood, but no bamboo farm and no spider. A stick is a quarter of a plank, so a
piece costs about 0.11 logs, and builders take their scaffolding down and reuse it (stock of 32 is 8 crafts, 56 sticks, 3.5 logs): cheap enough to be no obstacle in a
first colony, dear enough that a player with bamboo and string keeps using the vanilla recipe. The ratio is two lines in the datagen providers if it needs to move.

**Why the gantry shape.** `S S / SSS / S S` is the vanilla ladder (7 sticks, 3 ladders): the game test caught the clash (the grid crafted a ladder). `SSS / S S / S S`
clashes with no vanilla recipe (the test checks the ladder grid still makes 3 ladders).

**How the colony makes it.** `crafterrecipes/sawmill/...` is a custom crafter recipe: `CheckForWorkerSpecificRecipes` adds it to every sawmill without anybody teaching it
(`sawmill_crafting` is the custom recipe key of the sawmill's crafting module). A builder's scaffolding request that no stock covers is resolved by the sawmill's
production resolver: the sawmill asks the warehouse for 7 sticks (a stick request, itself craftable from planks if the player taught the sawmill that), crafts, and a courier
brings the 4 pieces to the builder hut. Tested end to end (`sticks_scaffold_sawmill_chain`: 14 sticks in a warehouse, a builder hut asking for 4 scaffolding, a sawmill
worker and a courier: the scaffolding is in the hut after about 1100 ticks, 7 sticks left).

## 2. Idle citizens as builder helpers

A citizen without a job helps a builder who is working on an order.

### The arbiter (reusable)

`com.minecolonies.core.colony.idle.IdleCitizenArbiter` decides what unemployed citizens do with their day, once a second per colony (`Colony.tickWorkManager`). A task is an
`IIdleCitizenTask` (`com.minecolonies.api.colony.idle`):

```java
interface IIdleCitizenTask { String getId(); int getPriority(); @Nullable IIdleTaskSession tryStart(IColony colony, ICitizenData citizen); }
interface IIdleTaskSession { String getTaskId(); boolean isValid(); void tick(); void stop(IdleStopReason reason); Component describe(); }
```

`IdleCitizenArbiter.register(task)` adds a task (same id replaces); tasks are offered every free citizen from the highest priority down, the first that returns a session
gets him. Builder helping (`BuilderHelperTask`, id `builder_helper`) is priority 100, the first registered task. The shared lifecycle lives in the arbiter, not in the tasks:

* free = no job, adult, entity loaded and alive, awake, not sick or hurt, not mourning, not hungry (above the restaurant limit), no raid, no rain, and inside the work
  hours (the citizen AI sends the unemployed to bed 2000 ticks before tick 12600, so the work hours are `clock <= 10600`);
* a session ends (`IdleStopReason`) when the citizen gets a job (`GOT_JOB`), the work hours end (`NIGHT`), the citizen AI moves him to sleep, food, care or mourning
  (`NEEDS`, see below), the colony setting goes off (`SETTING_OFF`), the task has nothing for him any more (`TASK_INVALID`), or he is gone (`CITIZEN_GONE`);
* after a `NEEDS` or `NIGHT` end the citizen is not offered a task for 400 ticks;
* the citizen AI (`CitizenAI`) has a new state `CitizenAIState.LABOUR` for a citizen with a session: the needs (sleep, sick, eating, mourning, rain) rank above it, and
  `decideAiTask` calls `IdleCitizenArbiter.leftLabour` when the AI moves him out, so a session ends the moment a need takes him; every AI step in `LABOUR` runs
  `session.tick()`.

Server thread only; per colony: a task is only ever asked about citizens of the colony being ticked, and `BuilderHelperTask` refuses a citizen of another colony outright.

### The builder helper task

On the builder collaboration (leases, territories, steal, the helper gate). A citizen joins the nearest order (within 96 blocks) that a builder leads, is in a shared stage
(clear, build, fit, decorate), has at least `collaborationMinRemaining` blocks left, has fewer idle helpers than the colony allows and passes the helper gate
(`collaborationMinOrderScore`, 600 by default, unless the player set a helper limit for the order). He never creates, claims or takes an order and never requests anything.

In the shared order state he is known by an `IdleHelperKey` (a block position no hut can have: x -33,000,000, y -2000, z = citizen id) where a builder helper is known by his
hut; leases, territories (`divideStage`), the helper lists, the reservation ledger and the lead's steal logic all key by it. The work is the helper's work of a builder
(`BuilderHelperSession`): lease a few positions of the stage out of the lead's stream (the lease size grows with the walk to the lead's hut, like a builder helper's), take
the materials for them from the lead's hut (what the ledger holds for others stays) or from the lead himself, walk to a spot on the ground from which he reaches each
position (3D reach), place or break it (a clear-stage block breaks with the best fitting tool the lead carries: the tool wears through the damage path of the citizen tools
(`CitizenItemUtils.damageInventoryItemForMinedBlock`: the item's own `Item#mineBlock`, the colony's tool durability research), exactly as when the lead mines the block, and nothing
of the helper's own wears; the drops go into the helper's inventory), release the lease. What he
cannot do goes back to the lead: positions higher than what he reaches from the ground (builders raise scaffolding for those), a material nobody has, a position with no
spot. When he stops, his leases go back, his claims on the ledger end, and what he carries (materials he did not use, drops of blocks he broke) goes into the lead's
hut. If the lead is short of a material that a helper carries he takes it from the helper's inventory instead of requesting it again (no double requesting).

**Speed.** `builders.collaborationIdleHelperSpeed` (default 0.6): he earns work time at 0.6 times the pace of the lead (the same pace the lead's AI counts its waits with: pace
provider times worker speed) and a block costs him what it costs the lead (his skill, research, builder speed), so he places and breaks at 0.6 of the lead's speed.

**Settings** (town hall settings window, saved with the colony, existing colonies get the defaults):

| setting | default | |
|---|---|---|
| `idlehelpers` (Idle citizens help builders) | on | off: no idle helpers, sessions end within a second |
| `idlehelperscap` (Most per builder) | 2 (0 to 8) | most idle helpers of one order's lead; the player's helper limit for an order, when set, counts builders and idle helpers together |

Server config (`builders` category): `collaborationIdleHelperSpeed` 0.6 (0.1 to 1.0). The general builder collaboration switch (`collaboration`) still governs everything.

**Views.** `ICollabView.IAssistantView.idleCitizen()` (and `maxIdleAssistants()`), `IWorkOrderView.getAssistantIdle()` (the client view of an order): the helper's "hut" is only his
key, his citizen id is the citizen himself. Colony Command shows him as "Helper (idle citizen)" in the crew strip and panel.

### Measured

`idle_helper_faster` builds the same 16 by 16 floor five times at the same place (a warm-up, then with the setting off and on): a builder alone 2140 to 2300 ticks, with an idle
citizen 1480 to 1720 (he places 80 to 100 of the 256 blocks): about 22 to 30 percent faster (the test waits for the path finder every tick, so the numbers do not swing with the load
of the machine). The gate matters: a flat structure like this one is below
`collaborationMinOrderScore` in a normal game, so the test lowers the gate (`BuilderCollab.overrideForTests`); in a normal game a small building keeps its builder alone (the
scaffold benchmark behind the gate found a helper no help on small and flat structures).

### Game tests (`MINECOLONIES_GAMETEST_ONLY=idlehelper`, `=sticks`)

`idle_helper_*`: faster with the helper and no helper with the setting off, no order means nothing happens, an order nobody leads is not claimed, leaves when given a job,
at night, when hungry, the setting off, the per builder limit (and the player's limit), two colonies in one world, the arbiter's task API, breaking and placing (and the drops go back
to the lead's hut), the borrowed tool wears like the lead's own, saved state.
`sticks_scaffold_*`: the table recipe (and the ladder and the vanilla recipe unharmed), the sawmill knows its recipe, the sawmill chain.
