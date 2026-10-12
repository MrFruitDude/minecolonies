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

## Attempts

(table below, newest last)

