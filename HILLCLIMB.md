# Request-system hill-climb log

Branch `perf/rs-hillclimb`. Instrument and rules: `BENCH.md`. Every attempt is logged here, accepted or not.

Method: quick bench (`tools/rs_bench.sh quick --reps 3`) of the candidate against the current best; keep only when the targeted
metrics are BETTER (median moved >= 3%, >= 3 SE, Mann-Whitney p <= 0.05), no score component is WORSE and every gate is green;
bigger changes are confirmed in full mode. Hangs are a hard gate (0 allowed, warm-ups included). The score formula and the gates
are never touched.

## Phase 0: make the benchmark trustworthy

### (a) "items appeared" at tick 300 in the busy conservation gate

Benchmark accounting, not a product bug: the workers already held items when the run started (and the first haul of a scripted
supplier landed in a rack the ledger had not seen), so units "appeared". Fixed before this branch point by commit `b77f4a7a3c`
(items the workers already hold at the start count as starting stock); since then `conservationMaxDrift` is 0 in every repeat of
every run (8000+ checks per storm repeat) and no log contains an "appeared" message.

### (b) storm never finishes, intermittent busy hangs, stranded planks -> fence craft

Root cause: a benchmark artifact, found in the hang report (each crafter's recipes): at every hang the sawmill "knew" `crimson_hyphae`
or `air` instead of `oak_planks`, the stonemason `warped_hyphae` / `prismarine` instead of `stone_bricks`, and the wait diagnosis said
`oak_planks recipeSupport=0 ... NO_SOURCE`: nobody could make planks, so the planks, bricks and fence requests (and the builders needing
them) waited forever. All hangs seen in 19 kept runs were in the first repeat of a scenario (a warm-up), which runs after other
scenarios in the same JVM.

Why: the benchmark makes request tokens reproducible (`StandardToken.seedForBenchmark`: same ids in every repeat, so hash iteration
order is the same). `RecipeStorage` took its token from the same generator, and the recipe manager is process-wide
(`IColonyManager.getInstance().getRecipeManager()`, a `BiMap<token, recipe>` that outlives every repeat). A recipe made later with
counter value n replaced an older, unrelated recipe that sat under token n (`BiMap.put` replaces); crafters then held a token that
now meant a different recipe. In the shipped game tokens are random UUIDs, so this cannot happen there; it is not a product bug.

Fix: recipe tokens are always random (`RecipeStorage` asks the token factory with `UUID.randomUUID()`); request tokens stay seeded.
Test, failing first: `rs_recipe_tokens_seeded` (`RequestSystemGameTests.recipeTokensAreNotReproducibleWhileTokensAreSeeded`, in the fork
suite) and the same body as `rsb_harness_recipe_tokens`, which now runs first in every bench JVM. Failed before the fix ("two different
recipes were given the same token while tokens were seeded"), passes after. Full run after the fix: 0 hangs in 28 macro repeats (7 per
scenario, warm-ups included) where the run before had a storm hang in the first repeat.

Not a cause: the 7 "Entity complete stuck ... teleport:true" warnings in storm (builders and consumers standing at y=4, two blocks below
the stone floor at y=5 they path on, around the plot). They happen with and without hangs, recover by themselves through the stuck-teleport,
and do not touch a request; the likely source is the builders' decoration work orders carving the floor of the test plot. Left alone.

### (c) References

`tools/rs_bench_ref.json` (full, K=5) = `bench-results/8808fb5475-full-20261012T014357Z.json` (measured on 13bdbc125e + the recipe-token
fix, the RS code of commit 8808fb5475). `tools/rs_bench_ref_quick.json` (quick, K=3) = `bench-results/8808fb5475-20261012T015415Z.json`.
The original baseline of the whole effort stays `bench-results/b77f4a7a3c-20261011T200053Z.json` (the final comparison is against it).
Run-to-run difference of the unchanged code against that baseline: score 96.4 (macro x0.977, micro x0.937), i.e. the noise floor of
the instrument on this machine is a few percent on the score and 10-40% on single micro costs when other workers load the machine.

## Measuring on a shared machine (what was learned in the first hours)

- The quick bench with K=3 can never give BETTER (the smallest two-sided Mann-Whitney p for 3 vs 3 is 0.1): use K=5 (`--reps 5`).
- The machine (6 cores) is shared with other workers' builds and game runs. The same code measured an hour apart differed by up to
  +-20% in macro RS CPU and by +300% in single micro operations (a whole 5-repeat run slow together). A comparison against a run taken
  hours earlier is therefore worthless; two runs a few minutes apart ("paired", baseline first, candidate right after) are
  the only comparison that held up. Verdicts below are from paired or adjacent runs unless said otherwise.
- Allocation per operation (`allocB`, deterministic) and payload bytes are not affected by the load and are used as supporting evidence.

## Attempts (newest last; every attempt, accepted or not)

| # | change (commit) | verdict | evidence (paired quick K=5 vs the code without it) |
|---|---|---|---|
| 1 | retrying resolver keeps delays as due-times in buckets (`66a6683509`, amended to `a5050d4cb7`: its serializers asked for `getDelays()`, now a copy, once per entry, which made save 2x slower at 1k) | REJECTED | idleTick@100 +2.0%, idleTick@1000 +3.3% WORSE, reevalInTick@100 +1.8%, @1000 -3.5% (noise), save@1000 +11.5% WORSE, assign@1000 +11.2% WORSE, macro rsCpu busy +2.8% / storm -7.9% / fresh -9.0% all noise, score 99.9 vs 100. The one earlier run that looked good (-12% idleTick) was an unpaired comparison against an adjacent baseline; the paired rerun did not reproduce it. Commit kept on `origin/rejected/rs-retry-buckets`. |
| 2 | view sync keeps the serialized request entries between snapshots; a request is written again only when its modification count moved, cache dropped on every full payload (`2a82bd2e3c` -> `0ae8f87dbb`) | ACCEPTED | vs adjacent run of the code without it: viewSnapshot@100 -58.6%, @1000 -74.6% (BETTER p=0.008); viewPerDirtyTick@100 -54.4%, @1000 -62.4%; macro busy rsCpu/tick 14.7k -> 13.0k (-11.3% BETTER), rsCpuP99 -13.4%; storm rsCpuP99 -28%, rsAlloc/tick -20.1% (BETTER). Noise in the same run: fresh rsCpu +101% (fresh also doubled in an unrelated earlier run on the machine, a load artefact; to be rechecked in the full run), single micro ops +10..14% at 1k (assign, idleTick) that the change cannot touch. Gates OK. `reqsync_*` GameTests pass (5). Quick score 99.7 vs 100 only because of the fresh outlier (macro x1.044), micro x0.894. |
| 3 | serializer and creator factories cached by class; `StaticLocation` caches its dimension id string (`549af209ed` -> `0e24d570a5`) | ACCEPTED | paired (run of attempt 2 code, then this): viewSnapshot@100 -46.3%, @1000 -39.3%, viewPerDirtyTick@100 -38.1%, save@100 -16.2%, @1000 -20.2%, create@100 -11.0% (all BETTER p<=0.016); macro busy rsCpu/tick 13.4k -> 11.5k (-13.8% BETTER p=0.032), rsCpuP99 -23.1%; storm rsAlloc -7.0%. Small WORSE flags on unrelated ops (assign@1000 +6.4%, idleTick@1000 +4.5%; `assign` also reads +6..14% WORSE in every second run of a pair, an order/load effect). Gates OK. Quick score 94.1 vs 100. |
| 4 | saving the request identities is linear: the entries were copied into a `HashSet`, and the entry hash of this map is key hash xor value hash, which is the same hash twice, so every entry hashes to 0 and the set was one bucket (`9144669d3b` -> `c7f4a4091e`) | ACCEPTED | save@1000 38.1M -> 4.15M ns (-89.1% BETTER p=0.008), save@100 -26.8%; the 10k scale (6 s per save at the baseline) is checked in the full run. Run taken right after attempt 3's candidate run. Also in this run: complete@100 -10.1%, stockEventInline@1000 -27.2% (BETTER), fresh rsCpu +85% WORSE (load artefact again: only runs started outside a pair show it; fresh is 2.9 us/tick). Gates OK. |
| 5 | resolver search orders its candidates once (no comparator walking a `LinkedList` with `indexOf`), request types of crafting resolvers are constants instead of `TypeToken.of` per call (`36c45f11dc` -> `7c4d7315a3`) | ACCEPTED | paired: assign@100 -44.0%, @1000 -50.2%; createAndAssignTotal@1000 -43.4%; idleTick@100 -40.2%, @1000 -42.4%; reevalInTick@1000 -31.6%; stockEvent@1000 -22.6%; macro busy rsCpu/tick 15.3k -> 10.7k (-30.4% BETTER), rsCpuP99 -24.6%; storm rsAlloc -10.8%. WORSE flags on ops the change cannot touch: complete@100 +42% (9.8k vs 14k, the base run sat at the low end of its usual 10-15k), ledgerCommit@1000 +207% on a 1.1-3.7 us operation that is bimodal between runs (cv 65% in the base run), viewDelta@1000 +11%. Gates OK. Composite quick score 99.2 vs 100 is dominated by those unrelated micro flags (micro x1.027) while macro is x0.977. |
| 6 | token-keyed lookups of requests, resolvers and data stores ask the map once (`b50f2e5c1e`) and the data store lookup makes no supplier unless it must create the store (`cd0da980ee`) -> `c94b2b6216` | ACCEPTED | two paired runs. Full quick pair: assign@1000 -8.9% BETTER, idleTick@1000 -6.8% BETTER, courierPick@1000 -7.1% BETTER, macro busy rsShare -13.3% BETTER; the 100-open-requests micro of the candidate run was polluted by load (every op +30..560%, view ops x4: not the code), so micro-only pair repeated: assign@1000 -10.2%, idleTick@1000 -6.8%, attempt@100 -21.5% (all BETTER p<=0.016); ledgerRead@1000 +10.0% WORSE in that run and -28.6% in the previous one (noise, the change does not touch it). Small change, kept on two consistent, mechanism-matching signals. Gates OK. |
| - | courier decides on its next task at once after finishing one (`9f2697ce19`, "RS4") | NOT RUN, held back | it changes how fast couriers work (up to 5 s less idle per task), i.e. gameplay timing and the ticksToCompletion component, not RS efficiency; the brief says to keep RS semantics. Left in `perf/rs-hillclimb-dev` for the owner to decide. |
| 7 | view sync reuses one write buffer for the thousands of small byte arrays of a snapshot (was a new `Unpooled.buffer()` growing by doubling each; `398844fd66`) | ACCEPTED (on allocation evidence) | profile first (1 ms JFR, busy+storm): 27% of RS CPU is `sendRequestSystemPackets`, 18% `snapshot`; 21% of RS allocation is `RequestSystemViewSync.bytes`. Paired quick run: macro rsAlloc/tick busy 6386 -> 4182 (-34.5% BETTER), storm 5514 -> 4181 (-24.2% BETTER), fresh 1206 -> 929 (-23.0%): allocation is load-independent. CPU verdicts are unusable for this pair: the base run was a polluted one (every micro op of the first scale 20-85% slower than in its neighbours), so viewSnapshot@100 -78% etc. overstate it; macro busy rsShare +31% WORSE and ticksToCompletion +13% WORSE in the candidate run come with 2 hung warm-up/measured repeats (see "Hang" below), not with this change. Gate: FAILED in that run (hang, pre-existing, investigated below). |
| 8 | the storage-network courier resolver tests whether a request's requester is a warehouse by type; it used `getBuilding(pos, IWareHouse.class)`, whose `ClassCastException` is thrown, caught and logged WITH A STACK TRACE for every non-warehouse requester (`546ec2d220`) | ACCEPTED | found because `DeliveryRequestResolver.canResolve` was 13% of busy RS CPU at 35 kB allocated per call. Log of the base run: 234 "getBuilding called with wrong type" stack traces (27.9k lines), candidate run: 0 (13.6k lines). Scope timings, busy: `canResolve` 1853 -> 700 ns/tick (-62%); storm assign -13%, storm rsAlloc -11.7% BETTER, fresh rsAlloc -28.9% BETTER. In the same paired run every OTHER busy scope ran 40-90% slower in the candidate (create +85%, ledgerWrite +68%, reeval +91%): a load window, so the busy rsCpu verdict (+28%, noise) is not about this change; the accepting evidence is the scope that only this change touches plus the deterministic exception count. Gates OK. |
| 9 | a request hands out one cached immutable list of its children until they change (`AbstractRequest.getChildren` built a new `ImmutableList` per call, 5.8% of RS allocation in the profile; `29dffcffa9`) | REJECTED | paired: busy rsAlloc/tick -4.7% (noise), storm +4.2% (noise), fresh +0.5%; no targeted metric BETTER (busy rsCpu -29% BETTER p=0.016 is not credible for a change worth <1% of CPU, and the micro-100 phase of that candidate run was polluted: every op +30..640%). Commit kept on `origin/rejected/rs-children-cache`. |
| 10 | a location writes its dimension id from bytes encoded once per id (the string was UTF-8 encoded for every `StaticLocation` in every snapshot; it was 25% of the serialization time in the micro profile; `6ea286e80d`) | ACCEPTED | paired: storm rsAlloc/tick 4056 -> 3656 (-9.8% BETTER), viewSnapshot@100 -16.9% (71.3k -> 59.3k ns, BETTER p=0.008), viewPerDirtyTick@100 -12.7%, viewDelta@1000 -7.6%. WORSE flags on ops it does not touch: complete@100 +10%, ledgerCommit@1000 (bimodal 1.2 / 4.4 us, cv 63%), ledgerRelease. Gates OK. |
| 11 | per-store version counters in the view sync: every plain data store (the six "raw" ones) is serialized again only when its counter moved, and every resolver entry only when its `viewVersion()` moved (`Tracked` collections + store versions, resolver versions; branch `fix/rs-courier-sync`) | ACCEPTED | paired quick K=5 vs the code without it (courier switch off in the candidate, so only the sync and the leisure fix differ): viewSnapshot@100 -52.3%, @1000 -57.1%; viewSnapshot2 (the snapshot after 1% changed) @100 -49.4%, @1000 -60.2% (all BETTER p=0.008); micro-only repeat: @100 -85.2%, @1000 -61.8%, viewPerDirtyTick@100 -47.4% (BETTER). Macro: busy rsShare -17.7% (BETTER p=0.032), busy rsAlloc/tick 2925 -> 2288 (-21.8% BETTER), busy rsCpuP99 -23.1%; fresh rsCpu/tick 3817 -> 1583 ns (-58.5% BETTER), storm rsCpuP99 -30.4% (BETTER); busy rsCpu/tick -11.8% and storm -8.0% are noise at K=5. Save format identical (save bytes 101k / 1.31M unchanged), client view equal to the full payload (`reqsync_*`, 5 GameTests + `reqsync_store_mutations` + `reqsync_quiet_stores_not_serialized`, JUnit `TrackedCollectionsTest`). WORSE flags: viewDelta/viewFull@100 (+419% / +248%) in the first run, which the repeat did not reproduce (neither function was touched; 100-scale first-phase noise), ledgerReserve/Commit/Read +6..18% (change cannot touch them). Profile after: `sendRequestSystemPackets` 27-35% -> 18.6% of RS CPU. Gates OK. |
| 12 | RS4: a courier decides on its next task at once after finishing one (`9f2697ce19`, cherry-picked; switch `requestSystem.courierImmediateNextTask`, default on, env `MINECOLONIES_RS_COURIER_IMMEDIATE`) | ACCEPTED by the user as a gameplay-timing change (not judged by the CPU verdict) | paired quick K=5, same code with the switch off, then on: storm ticksToCompletion 10.5k -> 7860 (-25.1% BETTER), storm latency p50 5560 -> 4270 (-23.2%), p99 -25.5%; busy latency p50 930 -> 700 (-24.7% BETTER), busy ticks -3.9% (noise, 6090 -> 5850; a busy repeat is limited by the builders' timing more than by the couriers); fresh unchanged. RS CPU per tick goes UP, as expected, because the same work is done in a shorter run: storm +39.6% / busy +32.6% per tick, busy alloc/tick +10.7%; the per-request costs fall (complete -29%, courierPick -56%, create -33% in storm: fewer waiting requests are re-evaluated). Gates OK, 0 hangs. Tests: `rs_courier_next_task_immediate` (the courier sits in the decision state 5 ticks between two tasks) and `rs_courier_next_task_delayed` (switch off: 100 ticks, the old timing); failing first (without the change: 100 ticks, the test failed). |
| - | the courier leisure fix (below) | CORRECTNESS FIX, not judged by CPU | see "Closed: the stuck courier". |

## Result (full mode, K=5, flags on, merged with `origin/integ/anno` ec2d32a508)

`bench-results/abd6a775f5-20261012T065328Z.json`: gates OK, 0 hangs in 49 runs. Compared with the original baseline
`bench-results/b77f4a7a3c-20261011T200053Z.json`: **score 71.5 (macro x0.782, micro x0.580)**; against the re-recorded reference
(`8808fb5475`, the same code without the climb) 74.1.

| | baseline | now | |
|---|---|---|---|
| busy RS CPU / tick | 22.1 us | 10.1 us | -54% BETTER |
| busy RS CPU p99 tick | 629 us | 276 us | -56% BETTER |
| busy RS alloc / tick | 16.0 kB | 5.1 kB | -68% BETTER |
| storm RS CPU / tick | 30.7 us | 12.9 us | -58% BETTER |
| storm RS CPU p99 tick | 1.06 ms | 346 us | -67% BETTER |
| storm RS alloc / tick | 21.2 kB | 7.9 kB | -63% BETTER |
| fresh RS CPU / tick | 2.9 us | 1.5 us | -50% BETTER |
| unload RS CPU / tick | 1.9 us | 2.6 us | +38% WORSE: total RS CPU 34.5 -> 38.1 ms (+10%) but the run is 20% shorter (14.4k ticks, builder timing of the merged integ/anno), so the per-tick figure rises; alloc/tick -40% |
| micro @10k: assign / idleTick / courierPick / complete | 43.8 us / 14 ms / 5.9 ms / 501 us | 24.4 us / 7.5 ms / 3.4 ms / 248 us | -44% / -46% / -42% / -51% |
| micro @10k: save | 6.0 s | 46 ms | -99% |
| micro @10k: view cost per dirty tick | 61.7 ms | 19.8 ms | -68% |
| ticks to completion, courier distance, requests created | | | unchanged within noise (the RS got cheaper, nothing it does moved), except fresh/storm requests +2..5% and fresh courier distance +8.8%, which come with the builder changes of the merge |

Worse than baseline: micro ledgerCommit @10k +28% (8.8 -> 11.2 us, a low-noise reading); it was +30% already in the first run on the fixed code before any
change of the climb, so it is a property of that code/baseline pair and not of an attempt (not investigated).

## Result of this round (full mode, K=5; paired: `a9d58ba767` = the code above, then this branch, same machine, adjacent runs)

`bench-results/full/a9d58ba767-20261012T170234Z.json` (base; its busy repeats 5 and 6 tripped over the trail NPE and ended early, gates OK) and
`bench-results/full/63a6fa8849-20261012T170823Z.json` (this branch, defaults: courier switch on): gates OK, 0 hangs. Score 94.0 against the base (macro x0.941, micro x0.939).
The same branch with the courier switch off (`...T171553Z.json`, its micro-100 phase polluted by load, its storm repeat 0 lost to "could not spawn a citizen at tick 200"):
busy RS CPU/tick 13.6k -> 8.8k ns (-35.0% BETTER), busy rsAlloc/tick -23.8%, busy p99 -37.1%, storm rsShare -8.5%, storm rsAlloc -10.2%: that is the view sync.

| | base | this branch (switch on) | |
|---|---|---|---|
| view snapshot per dirty tick @100 / @1000 / @10k | 62.9 us / 619 us / 11.2 ms | 10.6 us / 206 us / 3.7 ms | -83% / -67% / -67% BETTER |
| view cost per dirty tick (snapshot + delta) @100 / @1000 / @10k | 89.8 us / 1.32 ms / 21.7 ms | 36.0 us / 0.90 ms / 13.8 ms | -60% / -32% / -37% BETTER |
| busy RS CPU / tick, p99 tick | 13.6 us, 337 us | 11.5 us (-15%, noise), 250 us (-25.8% BETTER) | |
| fresh RS CPU / tick, alloc / tick | 1702 ns, 541 B | 1415 ns, 471 B | -16.9% / -13.0% BETTER |
| storm RS alloc / tick | 7384 B | 7027 B | -4.8% BETTER |
| storm view bytes sent | 1.19 MB | 782 kB | -34.5% BETTER (faster run) |
| storm ticks to completion | 23.2k | 18.1k | -21.8% BETTER (courier switch) |
| storm / busy request latency p50 | 11.8k / 1910 | 9250 / 1640 | -21.9% / -14.1% BETTER |
| storm RS CPU / tick | 12.4 us | 12.9 us (noise) | same work in a 22% shorter run |
| storm `stuckRoots` (trees unchanged for 3000 ticks) | 113 | 237 | WORSE by the verdict rule; the switch off gives 113 again, so it comes with the courier switch; every storm repeat finishes (0 hangs), the scripted stock arrives in 5 bursts at fixed ticks and a faster courier leaves more requests waiting for the next burst; not investigated further |

Micro @100/1000 WORSE flags that the changes cannot touch (complete +26%, stockEventInline +31..40%, ledgerRead +17.7%, save@1000 +12.8%, idleTick@1000 +3.5%): the usual load pattern of this machine
(the same ops move +-20% between two runs of identical code; the paired quick runs above show them flat).

## Closed: the stuck courier was a courier on a leisure break

Seen before: 2 of 7 busy repeats (one quick run), once before the climb, ~1-2% of repeats with a drain that never finished; 2 requests (a `cooked_beef x8` stack and its
delivery) stayed open, the courier holding the delivery sat in `PREPARE_DELIVERY` "walking" to the pickup rack 22-33 blocks away, moved ~67 blocks in 2000 ticks.

Reproduced on the first try of the branch (20 quick busy repeats, three times in a row, with a new "stall trail" in `MacroRun`: from the moment a request tree stood still for 1500
ticks the couriers' state is sampled every 50 ticks, in the whole run, not only in the drain): 1 hang of 19 repeats and `stuckRoots` > 0 in 3 of 19, one repeat finishing at 10365 ticks
instead of ~5500. The trail of the hung repeat shows the courier (AI state `PREPARE_DELIVERY`, task `cooked_beef x8`, 20-30 blocks from the rack) running **`PathJobRandomPos`** paths of
12-15 nodes, one every ~250 ticks, standing still 100 ticks in between: a wander, not a walk to the rack. No code of the courier starts a random walk; the citizen AI does:
`CitizenAI.calculateNextState` sends a worker to `IDLE` (-> `EntityAICitizenWander`, leisure site, 3 minutes) whenever `leisureTime > 0` (set at random, about once per two hours of game time per
citizen) and the worker AI says it may be interrupted. `PREPARE_DELIVERY` is "okay to eat" = interruptible. While the citizen is not in `WORKING` its worker AI does not tick, so the
`CourierWatchdog` (which only runs from `getCurrentTask`) never fires, the task sits in this courier's own queue (the only place it is kept) and the other 5 couriers idle next to
a full warehouse. A break is 3600 ticks plus the walk, longer than the bench's 4000-tick drain, and a real colony waits that long too.

Fix: a courier that holds a task takes no leisure break (`AbstractEntityAIBasic.canTakeLeisure()`, overridden in `EntityAIWorkDeliveryman`: `super && taskQueue.isEmpty()`; `CitizenAI` asks it
next to the old interruptible check). Meals, rain, night and sickness still send it away as before. Test, failing first: `rs_courier_leisure_keeps_task` (a leisure break is due the tick the
courier takes its task; the delivery to the builder must arrive within 1500 ticks) failed before ("... it is on a leisure break (leisure 2100, queue 1, worker AI PREPARE_DELIVERY)"),
passes after (delivered 260 ticks after the task was taken).

Evidence in the bench: samples of a courier with a task running a random-walk path: before 105 in 3 of 19 repeats, after 0 in 62 repeats; 0 hangs in 184 busy repeats (3 runs of 60, all gates OK;
before: 1 of 19). `stuckRoots` (a request tree unchanged for 3000 ticks) stays in ~15% of busy repeats: those are not stuck couriers (their trails show couriers walking with tasks, queue 1,
`PathJobMoveToLocation` / `PathJobMoveCloseToXNearY`), they are deliveries that take long under contention (6 couriers for 17 consumers), which is the bench's busy scenario working as designed.

Found on the way: `MacroRun.recordCourierTrail` threw `NullPointerException` (`PathResult.getPathLength` on a path not computed yet) in 3 of 61 repeats of one run; the repeat was then reported as "tick failed". Guarded.

Not changed (a gameplay decision, left to the owner): a courier that is sent to eat, sleep or hide from rain while it holds a task still strands that task for the duration. A guard that gives the task back to the pool when the owner's AI has not ticked for N ticks would cover those too.

## Remaining hot spots (busy / storm, share of RS CPU after the climb)

1. `sendRequestSystemPackets` is 18.6% of RS CPU after attempt 11 (was 27-35%). What is left: the retrying resolver (its delays count down every request-system tick, so its entry changes at every snapshot while anything waits), the full payloads (`writeStoreWhole` is 18% of RS allocation), `StandardFactoryController.serialize` 2.9%, the per-request `modCount` scan of the whole request store and `writeDelta`'s walk over every entry.
2. `assign`: 41-44% inclusive, of which `canResolve` 17% (courier resolvers' storage-network checks, `WaitDiagnosis.recipeSupport` scanning every crafting module's recipes per starved request, `ArrayList.sort` of candidates).
3. The retry ladder (`StandardRetryingRequestResolver.tick` -> `reassignRequest`, 12% of RS): real re-assignments; a bucketed due-time clock (attempt 1) was not faster.
4. `EntityAIWorkDeliveryman.prepareDelivery/deliver` (9% of RS): item-handler walks per delivery.
5. `courierPick` 6-10%: scans every queue in range (storage network).
6. `writeDelta` 5%: byte-array comparison of every entry.
7. Kept in `perf/rs-hillclimb-dev`, not applied: courier decides on its next task at once (`9f2697ce19`): a gameplay-timing change.


## Fork GameTest suite

On the merged branch (all accepted attempts + `ec2d32a508`): run 1 failed `worker_eff_bench_clear_l1` ("box not complete on tick 9300"), run 2 failed
`scaffold_two_builders_big` ("missing 2 on tick 9755"), run 3: **All 280 required tests passed** (279 of integ/anno + `rs_recipe_tokens_seeded`). The
`worker_eff` group alone passes (15/15), plain `origin/integ/anno` passed 279/279 in one run. Both failures are builder-AI tick-budget tests (not request-system
tests) and each ran at a time the machine was loaded by other workers; they did not repeat. Not proven load-only: a fourth run on each side would settle it.
