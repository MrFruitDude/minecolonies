# Request-system stress benchmark

A deterministic measuring instrument for the MineColonies request system (RS): correctness gates, per-operation costs
at 100 / 1k / 10k open requests, and four macro scenarios with real colonies. It exists to hill-climb RS efficiency;
it does not optimise anything itself. See `HILLCLIMB.md` for the hill-climb log. Baseline: `bench-results/b77f4a7a3c-20261011T200053Z.json` (full; quick: `bench-results/quick-baseline-1edf009567.json`; integ/anno
`f0583ee0d3`, flags at the shipped defaults: reservations, smart retry, priority classes on).

## Run it

```
tools/rs_bench.sh quick                    # ~75 s of game time (micro 100/1k + smaller fresh/busy/storm, 2 repeats), prints the summary
tools/rs_bench.sh full                     # K=5: micro 100/1k/10k + fresh, busy, storm, unload; ~8 min of game time
tools/rs_bench.sh full --reps 3 --only busy,storm      # subset (test-name words: micro, fresh, busy, storm, unload)
tools/rs_bench.sh full --flags off         # RS reservation/smart-retry switches off (the player-fallback gate then fails: expected)
tools/rs_bench.sh full --jfr "/tmp/rs-%p.jfr" --only busy,storm   # JFR; then: python3 -I tools/rs_bench_jfr.py /tmp/rs-<game pid>.jfr
python3 -I tools/rs_bench_report.py compare bench-results/<base>.json bench-results/<new>.json   # table, verdicts, score
python3 -I tools/rs_bench_report.py show bench-results/<file>.json
```

The run is one `runGameTestServer` under `flock /mnt/data/anno117-extract/.cc-gametest.lock` and `timeout 3300`; waiting for
the lock can take a long time when other workers run GameTests. Results go to `bench-results/<git-sha>[-dirty]-<timestamp>.json`
(`--keep-raw` keeps the per-repeat JSON in `bench-tmp/`, git-ignored). Exit code is non-zero when a gate failed.
Settings are environment variables (`RS_BENCH_ONLY/MODE/REPS/SEED/FLAGS/WORKERS/OUT/ONLY_TESTS`, see `BenchEnv`);
the runner sets them. `RS_BENCH_ONLY=1` makes the registrar register only the `minecolonies:rsb_*` tests.

## What is where

| | |
|---|---|
| `core/colony/requestsystem/RsProbe.java` | the only production-side instrument: scoped CPU / allocation timers at the RS entry points. One static boolean read per hook while off (always, outside the benchmark). Scopes: create, assign, canResolve, attempt, stateChange, mgrTick, reeval, stockEvent, courierPick, courierDone, ledgerWrite, ledgerRead, viewSync, snapshot, save, load, colonyUpdate. Hooks are wrappers (`x()` -> `x0()`), so no logic changed. |
| `RsStats` (extended), `StandardToken.seedForBenchmark` | counters (items delivered, deliveries arrived, fallback tokens); reproducible token ids so hash iteration order is the same every run. |
| `core/gametest/bench/` | `MicroBench`, `MacroRun` + `Scenarios`, `ItemUnits` (conservation), `RsDigest` (save/load identity), `RepeatRunner`, `BenchRegistrar`, `BenchEnv`; `core/gametest/BenchBridge` opens the package-private fixtures. |
| `tools/` | `rs_bench.sh`, `rs_bench_report.py` (merge, score, compare), `rs_bench_jfr.py` (hot spots from a JFR), `rs_bench_ref.json` (score reference = the baseline). |

## Tier 1: micro (the real manager, no world simulation)

A real colony (never ticking: driven by hand) with a builder, a warehouse with 12 racks, a sawmill with a recipe, a lumberjack
(so waiting requests wait instead of falling to the player), a courier, and 8 requester huts. A seeded script of 60 item
kinds: pre-fill to N-M open requests, then per operation class (probe scopes, per-call samples, trimmed mean over the slowest
tenth; one-shot operations are medians of 3-5 runs): **create**, **assign** (resolver search), **idleTick** (manager tick with N waiting requests,
amortised over 90 ticks), **stockEvent** (stock lands in a rack + 3 ticks, paired with a control window without the event),
**courierPick** (`JobDeliveryman.getCurrentTask` with N deliveries queued), **complete**, **cancel**, **ledgerReserve / Commit / Read /
Release**, **view sync** (snapshot, full payload, delta after 1% changes; time, allocation, bytes), **save / load** (NBT; time, bytes).
Gates: save then load into a fresh manager gives an identical digest (requests, resolvers, assignments, ledger), no ledger claim
belongs to an ended request, every cancel took.

## Tier 2: macro (real colonies, fixed seed, citizens with AI)

All four share: a 112 x 96 plot, stone floor, a mock player near the town hall (the colony ticks and syncs its RS view like with
a player online), real workers (builders, sawmill, stonemason, couriers) with skills pinned to 8, entity RNG seeded, midday and clear
weather forced (couriers do not work in the rain), sick citizens cured, worker speed pinned to 1.0 and real walking
(`WorkerSettings`; `RS_BENCH_WORKERS=default` uses the server's settings), pathfinding results awaited between ticks so they arrive at the same tick
every run. Gatherers (lumberjack, miner, farmer huts) exist so the colony "can still make" things, but their output is scripted
(a haul into the warehouse every few hundred ticks). Crafting chain: logs -> planks -> fence (one sawmill, two recipes), cobblestone
-> stone bricks (stonemason). Builds are in-memory decoration work orders of planks / cobblestone / bricks / fences.

| scenario | what | measured work |
|---|---|---|
| `fresh` | fresh colony, zero materials: town hall, warehouse, 2 couriers (2 courier huts), builder, sawmill, stonemason, 3 gatherer huts; 6 hut builds queued (3 in quick) | ticks until all stand |
| `busy` | 30 buildings: town hall, **2 warehouses with 3 couriers each**, 3 builders with 9 overlapping orders (4 in quick), sawmill, stonemason, 3 gatherers, 17 small buildings (8 in quick) that keep asking for food (batches of 8) and tools (a stone pickaxe wears out every 600 ticks) | ticks until the 9 builds stand; demand runs until then |
| `storm` | **~480 open requests at once** (full): minimum stock of 120 items + planks + bricks at the warehouse, 14 buildings x 20 requests, 2 builders with 4 orders; only 3 couriers (2 in quick); stock then arrives in 5 bursts | ticks until every request is done |
| `unload` (full only) | `fresh` with 3 huts while the colony's chunks unload and reload every 900 ticks (player leaves, tickets released, 29 cycles) | ticks until the huts stand |

After the work is done demand stops and the run drains (up to 4000 ticks) until no request is open.

Per tick: server-thread CPU (minus the benchmark's own bookkeeping), the part inside the RS (probe overhead, calibrated per run, subtracted),
allocated bytes. Per run: requests created, deliveries created / arrived, courier tasks (trips), courier distance walked (position
deltas, teleports excluded), items delivered, player fallbacks, duplicate requests (open top-level requests with the same requester and item), stuck request trees (no change
for 3000 ticks), request latency p50/p99 (first seen to gone), peak/mean open requests, view-sync bytes, per-scope and per-resolver breakdown.

### Correctness gates (a failed gate marks the run INVALID and exits non-zero)

1. **Item conservation**, every 20 ticks and at the end: the "item units" in racks of all buildings, citizen inventories, loose item entities and the blocks of
   finished builds equal what the scripted suppliers added minus what scripted sinks removed. Units are preserved by every recipe used (log 8 = 4 planks x 2;
   fence 2 = plank; cobblestone = stone brick). A duplicate shows as a surplus, a loss as a deficit, and the failure lists what moved since the last good check.
2. **Completion**: every scenario finishes within its budget and the demand drains. Quirk, documented deliberately: the shipped system hangs a courier or craft
   now and then; a repeat that never finishes is reported (`gates.hangs`) with the state of workers, couriers (with a trail) and crafters' recipes, and makes the run invalid: hangs are a hard gate, none tolerated, warm-ups included. (Seen while building this: a same-crafter planks -> fence chain under planks contention stranded a request; couriers livelocking
   at distant consumers. Both were avoided in the scenarios, not fixed.)
3. **No avoidable player fallback**: any request that fell to the player resolver is a failure (nothing in the scenarios is unproducible). With `--flags off` this
   fails by design: that is the old behaviour.
4. **No ledger orphans**: no reservation belongs to a request that ended.
5. **Save/load identity** mid-run (when requests are open) and at the end: `RsDigest` of the live manager equals that of a manager loaded from its NBT.

## Score (lower is better; 100 = the reference run: `tools/rs_bench_ref.json` for full mode = the baseline, `tools/rs_bench_ref_quick.json` for quick mode, whose scenarios are smaller; scores are only comparable within a mode)

```
per macro scenario s:  R_s = exp( sum_i w_i ln(x_i / ref_i) )        components, medians over the measured repeats:
     rsCpuNsPerTick (0.40)   ticksToCompletion (0.30)   courierDistance until done (0.15)   requestsCreated until done (0.15)
MACRO = exp( sum_s v_s ln R_s / sum v_s )      v: fresh 1, busy 2, storm 2, unload 1
MICRO = geometric mean of x/ref over the micro costs at 1k and 10k open requests:
     create, assign, idleTick, stockEvent, courierPick, complete, cancel, ledgerReserve, ledgerCommit, viewPerDirtyTick (ns and bytes), save (ns and bytes), load
SCORE = 100 * MACRO^0.7 * MICRO^0.3          INVALID if any gate failed
```
`compare` prints every component with base/new medians, the relative spread (cv), the delta, and a verdict: BETTER/WORSE only when the median moved by at least 3% **and**
at least 3 standard errors **and** the Mann-Whitney exact p <= 0.05 (n <= 2 repeats skip the p test); otherwise "noise".
`python3 -I tools/rs_bench_report.py ref <result.json> tools/rs_bench_ref.json` re-bases the score to a new reference.

## Baseline (K=5 measured repeats, 2 warm-ups for macro / 2 for micro, integ/anno f0583ee0d3, flags on, workers pinned)

Macro (median, cv over 5 repeats). RS CPU is server-thread CPU inside the request system per game tick:

| | fresh | busy | storm | unload |
|---|---|---|---|---|
| ticks to completion | 13,045 (12%) | 12,625 (1%) | 23,490 (3%) | 17,606 (12%) |
| RS CPU / tick | 2.9 us (11%) | 22.1 us (7%) | 30.7 us (9%) | 1.9 us (7%) |
| RS share of server tick | 2.6% | 8.7% | 13.1% | 1.4% |
| RS CPU p99 tick | 20 us (21%) | 629 us (13%) | 1.06 ms (17%) | 11 us (9%) |
| RS alloc / tick | 1.2 kB (9%) | 16.1 kB (2%) | 21.2 kB (4%) | 0.8 kB (4%) |
| requests created | 88 (0.5%) | 414 (6%) | 352 (0.2%) | 53 (6%) |
| deliveries / courier trips | 32 / 31 | 203 / 162 (7%) | 302 / 160 (0.3%) | 18 / 17 |
| courier distance (blocks) | 1,334 (1%) | 11,819 (4%) | 10,178 (1%) | 810 (7%) |
| items delivered | 89 | 729 (7%) | 1,350 | 48 |
| player fallbacks / duplicates | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| peak open requests | 12 | 73 | 477 | 12 |
| request latency p50 / p99 (ticks) | 820 / 1,560 | 1,450 / 3,590 | 11,410 / 23,090 | 790 / 2,490 |
| view-sync bytes sent | 54 kB | 295 kB | 1.27 MB | 230 kB |

Where the RS CPU goes (self time of probe scopes, share of RS): view snapshot 26-47%, assign (resolver search) 8-19%, view sync (delta, send) 10-19%,
manager tick / re-evaluation 3-23%, courier pick 4-6%, state changes 3-11%, canResolve 2-6% (70,797 calls in `storm`).

Micro (median, cv over 5 repeats; N open requests):

| operation | 100 | 1,000 | 10,000 |
|---|---|---|---|
| create | 5.1 us | 3.1 us | 3.1 us |
| assign (resolver search) | 46 us | 45 us | 44 us |
| idle manager tick | 62 us | 1.13 ms | 14.0 ms (cv 8%) |
| stock arrives + 3 ticks | 148 us | 1.62 ms | 5.9 ms (cv 24%) |
| courier pick (N queued) | 5.4 us | 264 us | 5.9 ms (cv 12%) |
| complete (delivery -> parents) | 11.5 us | 38.5 us | 501 us (cv 28%) |
| cancel | 6.8 us | 6.7 us | 12.9 us |
| ledger reserve / commit / read | 0.1 / 1.1 / 3.6 us | 0.1 / 4.6 / 27 us | 0.1 / 8.8 / 285 us |
| view snapshot (per dirty tick) | 345 us | 4.8 ms | 52.9 ms (cv 20%) |
| view full / delta bytes | 29 kB / 0.9 kB | 358 kB / 4.2 kB | 4.2 MB / 39 kB |
| save to NBT | 0.72 ms | 42.6 ms | **6.0 s** (cv 37%) |
| save bytes | 101 kB | 1.3 MB | 15.3 MB |
| load from NBT | 1.1 ms | 8.9 ms | 91 ms |

(`save` is quadratic: x60 then x140 for x10 requests.) Probe overhead: one coarse scope pair costs ~1.7 us, a fine one ~60 ns; both are subtracted from the RS CPU
figures (the per-op micro figures are inclusive and still contain the nested scopes' overhead, constant across runs).

### Noise (what to expect from a K=5 compare)

Counts are the quietest: requests created, deliveries, tasks and items delivered vary 0-7% (storm/fresh < 1%). Ticks to completion: 1-3% in `busy`/`storm`,
~12% in `fresh`/`unload` (AI timing). RS CPU per tick: 7-11%. Micro costs: most 1-10%, the heavy-tailed ones (complete, stock event, courier pick, snapshot at 10k) 12-30%, `save` at 10k 37%. Rule of thumb: a change of less than ~10% in one run
set is usually noise; the compare table says so. The first measured repeat is slightly slower than the later ones (JIT); that is why 2 warm-ups run.

## Hot spots from a JFR profile of `busy` + `storm` (3 repeats each, profile settings; RS = 13.3% of server-thread CPU samples, 21.8% of allocation)

Ranked by share of **request-system** CPU (inclusive unless marked self):

1. **View sync serializes the whole RS every dirty tick** - `ColonyPackageManager.sendRequestSystemPackets` -> `RequestSystemViewSync.snapshot/identitySnap/bytes` -> `StandardFactoryController.serialize`: 38.8% of RS CPU, 23% of RS allocation (4 MB of garbage per snapshot at 10k requests). Delta bytes are small (39 kB at 10k), the work to find them is not.
2. **Serialization machinery** (inside 1): `StandardFactoryController.getFactoryForOutput` 7.0% self (type lookup per object), `StaticLocation.serialize` 5.6% self + 10% of RS alloc, `AbstractTokenFactory.serialize` 2.8%, `TypeTokenFactory...matches` 2.3%, `StandardDataStoreManager.get` 4.1% self.
3. **Resolver search per assignment** - `RequestHandler.assignRequest` -> `assignRequestDefault0`: 24.1% (builds a `LinkedList` of types, an `ArrayList` of resolvers per request, sorts with `typeIndexList.indexOf` in the comparator (`lambda$assignRequestDefault0$0` 1.8%), then `canResolve` (up to 70k calls in `storm`).
4. **Retry ladder re-runs the whole assignment** - `StandardRequestManager.tick0` 20.9% -> `StandardRetryingRequestResolver.tick` 17.8% -> `RequestHandler.reassignRequest` 16.1%: every waiting request is retried on its own backoff (idle tick: 14 ms at 10k requests, linear in waiting requests).
5. **`HashMap.getNode` / `StandardToken.hashCode`** (8.4% + 2.4% of RS): token-keyed lookups (`getRequestOrNull` 2.0%, `ResolverHandler.getResolver`, `HashBiMap.seekByKey`) on every step; the UUID hash is recomputed each time.
6. **`RequestSystemViewSync.writeDelta`** 3.9% self, `writeValue` 1.8%: the diff re-compares byte arrays of every entry.
7. **`EntityAIWorkDeliveryman.deliver`** 3.8% self (item-handler walk per delivery).
8. **Allocation: `TypeToken.of`** 10.9% of RS allocation (`AbstractCraftingRequestResolver.getRequestType` 5.1% on every `canResolve`/assign, `getSuperClasses`), `AbstractRequest.getChildren` 7.5% (a fresh `ImmutableList` per call).
9. **`WarehouseRequestQueueModule` / `JobDeliveryman.getCurrentTask`**: courier pick is linear in the queue (264 us at 1k, 5.9 ms at 10k queued deliveries).
10. **Persistence**: `StandardRequestManager.serializeNBT` is quadratic (6 s at 10k requests, 42 ms at 1k) and `deserializeNBT` linear but heavy (91 ms at 10k); also `complete` grows superlinearly (11 us -> 500 us), and the ledger read (`sum`) is linear in the claims of a scope (285 us at 10k).

## Known gaps / limits

- **Smelter missing**: crafters are sawmill (2 recipes) and stonemason; the smeltery / furnace AI is not set up. The fletcher rejects a fence recipe (crafters only accept recipes of their own trade), so a deeper
  chain than logs -> planks -> fence was not built.
- **Gatherers are scripted** (lumberjack, miner, farmer huts exist, workers are not simulated); consumption is scripted (requests created through `building.createRequest`, items removed from racks), not driven by citizen hunger or tool wear.
- **Runs are deterministic in inputs, not in outcomes**: pathfinding and AI make `fresh`/`unload` ticks-to-completion vary ~12% between repeats even with seeded RNG, pinned skills and synchronised path results.
- **Intermittent hangs of the shipped system** exist (a courier delivery that never completes; see Gate 2); the baseline run had none in 5 repeats but earlier trial runs of `busy` with consumers 40+ blocks from the warehouses hung in ~40% of repeats, so consumers sit within ~35 blocks.
- Tool requests (`Tool` requestable) are not used; consumers ask for plain stacks. Minimum stock is used only in `storm`.
- `--flags off` only switches reservations and smart retry; priority classes stay at the config default.
- Probe overhead (1.7 us per coarse scope) lands inside the inclusive micro figures; absolute micro numbers are therefore slightly high, relative changes are valid.
- The mock player is not a real connection: view sync packets are built and counted but never sent over a socket.
