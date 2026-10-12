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

