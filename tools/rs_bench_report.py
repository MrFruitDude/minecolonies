#!/usr/bin/env python3
"""Merge, score and compare request-system benchmark results.

  rs_bench_report.py merge RAWDIR --sha S --mode M --reps K --flags F --seed N --out FILE [--status RC] [--ref FILE]
  rs_bench_report.py compare BASE.json NEW.json [--all] [--min-pct 3]
  rs_bench_report.py show RESULT.json
  rs_bench_report.py ref RESULT.json OUT.json        (write a score reference from a result)

Run with `python3 -I`. Standard library only.

SCORE (lower is better, 100 = the reference run the score reference file was made from):

  per macro scenario s:   R_s = exp( sum_i w_i * ln(x_i / ref_i) )     (weighted geometric mean of component ratios; each component is the median over the measured repeats)
      components i (weight):  rsCpuNsPerTick (0.40)   mean server-thread CPU ns per tick spent inside the request system
                              ticksToCompletion (0.30) game ticks until the scenario's work is done
                              courierDistance (0.15)   blocks the couriers walked until then
                              requestsCreated (0.15)   requests created until then
  MACRO = exp( sum_s v_s * ln R_s )     scenario weights v: fresh 1, busy 2, storm 2, unload 1 (normalised over those present)
  MICRO = geometric mean over the micro operation costs (CPU ns per operation at 1k and 10k open requests, plus the bytes of
          one view delta and of one save) of  x / ref
  SCORE = 100 * MACRO^0.7 * MICRO^0.3

A run with a failed correctness gate has no valid score: it is reported as INVALID.
"""
import json
import math
import os
import statistics
import sys
import time

SCHEMA = 1

MACRO_COMPONENTS = [
    ("meter.rsCpuNsPerTick", 0.40),
    ("ticksToCompletion", 0.30),
    ("work.courierDistanceAtDone", 0.15),
    ("work.requestsCreatedAtDone", 0.15),
]
SCENARIO_WEIGHTS = {"fresh": 1.0, "busy": 2.0, "storm": 2.0, "unload": 1.0}
MICRO_OPS = [
    ("create", "cpuNs"),
    ("assign", "cpuNs"),
    ("idleTick", "cpuNs"),
    ("stockEvent", "cpuNs"),
    ("courierPick", "cpuNs"),
    ("complete", "cpuNs"),
    ("cancel", "cpuNs"),
    ("ledgerReserve", "cpuNs"),
    ("ledgerCommit", "cpuNs"),
    ("viewPerDirtyTick", "cpuNs"),
    ("viewPerDirtyTick", "bytes"),
    ("save", "cpuNs"),
    ("save", "bytes"),
    ("load", "cpuNs"),
]
MICRO_SCORE_SCALES = ("1000", "10000")


# ---------------------------------------------------------------------------------------------------------- helpers

def flatten(obj, prefix=""):
    out = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.update(flatten(v, prefix + k + "."))
    elif isinstance(obj, bool):
        out[prefix[:-1]] = 1.0 if obj else 0.0
    elif isinstance(obj, (int, float)):
        out[prefix[:-1]] = float(obj)
    return out


def stats(values):
    n = len(values)
    if n == 0:
        return {"n": 0, "values": []}
    mean = statistics.fmean(values)
    sd = statistics.stdev(values) if n > 1 else 0.0
    return {
        "n": n,
        "values": values,
        "mean": mean,
        "median": statistics.median(values),
        "sd": sd,
        "cv": (sd / mean) if mean else 0.0,
        "min": min(values),
        "max": max(values),
    }


def load_raw(rawdir):
    runs = []
    for name in sorted(os.listdir(rawdir)):
        if name.endswith(".json") and name.startswith("rsb_"):
            with open(os.path.join(rawdir, name)) as f:
                runs.append(json.load(f))
    return runs


def geomean(values):
    values = [max(v, 1e-9) for v in values]
    return math.exp(sum(math.log(v) for v in values) / len(values)) if values else 1.0


# ---------------------------------------------------------------------------------------------------------- merge

def merge(rawdir, meta, ref_path):
    runs = load_raw(rawdir)
    micro_runs, macro_runs = {}, {}
    gate_failures = []
    warmups = 0
    hangs = {}
    measured = {}
    for run in runs:
        if run.get("warmup"):
            warmups += 1
        for err in run.get("errors", []):
            gate_failures.append("%s: %s" % (run.get("test"), err))
        if run["kind"] == "macro":
            # hangs count in warm-ups too: a hang is a hang
            measured[run["scenario"]] = measured.get(run["scenario"], 0) + 1
            if run.get("liveness"):
                hangs.setdefault(run["scenario"], []).append("%s%s: %s" % (run.get("test"), " (warm-up)" if run.get("warmup") else "", run["liveness"][0].split("\n")[0]))
        if run.get("warmup"):
            continue
        if run["kind"] == "micro":
            micro_runs.setdefault(str(run["scale"]), []).append(run)
        else:
            macro_runs.setdefault(run["scenario"], []).append(run)

    micro = {}
    for scale, rs in micro_runs.items():
        flat = [flatten({"ops": r["ops"], "info": r.get("info", {})}) for r in rs]
        keys = sorted(set().union(*[f.keys() for f in flat]))
        micro[scale] = {k: stats([f[k] for f in flat if k in f]) for k in keys}
    macro = {}
    for scenario, rs in macro_runs.items():
        flat = []
        for r in rs:
            f = flatten({k: r[k] for k in ("meter", "work", "scopes", "extra", "gates") if k in r})
            f["ticksToCompletion"] = float(r["ticksToCompletion"] if r.get("ticksToCompletion", -1) >= 0 else r.get("ticksRun", 0))
            f["ticksToQuiesce"] = float(r.get("ticksToQuiesce", -1))
            f["ticksRun"] = float(r.get("ticksRun", 0))
            # per-resolver rows
            for row in r.get("byResolver", []):
                base = "byResolver.%s.%s." % (row["class"], row["scope"])
                f[base + "calls"] = float(row["calls"])
                f[base + "cpuNs"] = float(row["cpuNs"])
                f[base + "allocB"] = float(row["allocB"])
            flat.append(f)
        keys = sorted(set().union(*[f.keys() for f in flat]))
        macro[scenario] = {k: stats([f[k] for f in flat if k in f]) for k in keys}

    # A repeat that never finishes (a hang) is counted at its full budget and fails the run: no hang is tolerated.
    liveness_notes = []
    for scenario, notes in hangs.items():
        liveness_notes.extend(notes)
        gate_failures.append("%s: %d of %d repeats did not finish (none allowed): %s" % (scenario, len(notes), measured[scenario], notes[0]))

    result = {
        "schema": SCHEMA,
        "sha": meta["sha"],
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "mode": meta["mode"],
        "reps": meta["reps"],
        "flags": meta["flags"],
        "seed": meta["seed"],
        "serverExit": meta["status"],
        "warmupRuns": warmups,
        "gates": {"ok": not gate_failures and bool(runs), "failures": gate_failures, "hangs": liveness_notes,
                  "runs": len(runs)},
        "micro": micro,
        "macro": macro,
    }
    ref = load_ref(ref_path)
    result["score"] = score(result, ref)
    return result


# ---------------------------------------------------------------------------------------------------------- score

def load_ref(path):
    if path and os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def components(result):
    """The raw component values the score is made of."""
    comp = {"macro": {}, "micro": {}}
    for scenario, metrics in result["macro"].items():
        comp["macro"][scenario] = {k: metrics[k]["median"] for k, _ in MACRO_COMPONENTS if k in metrics and metrics[k]["n"]}
    for scale in MICRO_SCORE_SCALES:
        m = result["micro"].get(scale)
        if not m:
            continue
        for op, field in MICRO_OPS:
            key = "ops.%s.%s" % (op, field)
            if key in m and m[key]["n"]:
                comp["micro"]["%s:%s.%s" % (scale, op, field)] = m[key]["median"]
    return comp


def score(result, ref):
    comp = components(result)
    use_ref = ref["components"] if ref else comp
    out = {"components": comp, "reference": "self" if not ref else ref.get("sha", "?")}
    macro_logs, wsum = 0.0, 0.0
    per_scenario = {}
    for scenario, values in comp["macro"].items():
        r = use_ref["macro"].get(scenario, values)
        logs, w_total = 0.0, 0.0
        parts = {}
        for key, w in MACRO_COMPONENTS:
            if key in values and key in r:
                ratio = max(values[key], 1e-9) / max(r[key], 1e-9)
                parts[key] = ratio
                logs += w * math.log(ratio)
                w_total += w
        if w_total:
            ratio_s = math.exp(logs / w_total)
            per_scenario[scenario] = {"ratio": ratio_s, "parts": parts}
            v = SCENARIO_WEIGHTS.get(scenario, 1.0)
            macro_logs += v * math.log(ratio_s)
            wsum += v
    macro = math.exp(macro_logs / wsum) if wsum else 1.0
    ratios = []
    for key, value in comp["micro"].items():
        if key in use_ref["micro"]:
            ratios.append(max(value, 1e-9) / max(use_ref["micro"][key], 1e-9))
    micro = geomean(ratios) if ratios else 1.0
    out["perScenario"] = per_scenario
    out["macroRatio"] = macro
    out["microRatio"] = micro
    out["value"] = 100.0 * (macro ** 0.7) * (micro ** 0.3)
    out["valid"] = result["gates"]["ok"]
    return out


# ---------------------------------------------------------------------------------------------------------- compare

def mann_whitney_p(a, b):
    """Two-sided exact Mann-Whitney U p-value (ties get mid-ranks; exact over rank permutations only without ties,
    otherwise the normal approximation with tie correction)."""
    na, nb = len(a), len(b)
    if na < 2 or nb < 2:
        return 1.0
    allv = sorted([(v, 0) for v in a] + [(v, 1) for v in b])
    ranks, i = {}, 0
    ordered = [v for v, _ in allv]
    rank_of = []
    while i < len(ordered):
        j = i
        while j + 1 < len(ordered) and ordered[j + 1] == ordered[i]:
            j += 1
        rank_of.extend([(i + j) / 2.0 + 1.0] * (j - i + 1))
        i = j + 1
    ra = sum(r for (v, g), r in zip(allv, rank_of) if g == 0)
    u = ra - na * (na + 1) / 2.0
    has_ties = len(set(ordered)) != len(ordered)
    if not has_ties and na + nb <= 20:
        # exact distribution of U by counting subsets of ranks
        total = na + nb
        counts = {}
        from itertools import combinations
        n_all = 0
        for comb in combinations(range(1, total + 1), na):
            ua = sum(comb) - na * (na + 1) // 2
            counts[ua] = counts.get(ua, 0) + 1
            n_all += 1
        mean_u = na * nb / 2.0
        dev = abs(u - mean_u)
        p = sum(c for ua, c in counts.items() if abs(ua - mean_u) >= dev - 1e-9) / n_all
        return min(1.0, p)
    n = na + nb
    mu = na * nb / 2.0
    tie = 0.0
    from collections import Counter
    for t in Counter(ordered).values():
        tie += t ** 3 - t
    sigma = math.sqrt(na * nb / 12.0 * ((n + 1) - tie / (n * (n - 1)))) if n > 1 else 0.0
    if sigma == 0:
        return 1.0
    z = (abs(u - mu) - 0.5) / sigma
    return math.erfc(z / math.sqrt(2.0))


def verdict(a, b, min_pct):
    """a, b: stats dicts (lower is better). Returns (delta_pct, label, p)."""
    if not a or not b or not a.get("n") or not b.get("n"):
        return None, "n/a", 1.0
    ma, mb = a["median"], b["median"]
    delta = (mb - ma) / ma * 100.0 if ma else (0.0 if mb == 0 else float("inf"))
    se = math.sqrt((a["sd"] ** 2) / a["n"] + (b["sd"] ** 2) / b["n"])
    p = mann_whitney_p(a["values"], b["values"])
    big_enough = abs(mb - ma) >= abs(ma) * min_pct / 100.0
    clear = abs(mb - ma) >= 3.0 * se
    if big_enough and clear and (p <= 0.05 or min(a["n"], b["n"]) < 3):
        return delta, "BETTER" if mb < ma else "WORSE", p
    return delta, "noise", p


KEY_MACRO = [
    "meter.rsCpuNsPerTick", "meter.rsShare", "meter.rsCpuP99", "meter.rsAllocBytesPerTick", "ticksToCompletion",
    "work.courierDistanceAtDone", "work.requestsCreatedAtDone", "work.deliveriesCreatedAtDone", "work.courierTasksAtDone",
    "work.itemsDeliveredAtDone", "work.playerFallbacks" if False else "gates.playerFallbacks", "work.duplicatesMax", "work.stuckRoots",
    "work.latencyP50", "work.latencyP99", "work.viewBytes", "work.openMax",
]


def fmt(v):
    if v is None:
        return "-"
    if abs(v) >= 1e6:
        return "%.3gM" % (v / 1e6)
    if abs(v) >= 1e4:
        return "%.3gk" % (v / 1e3)
    if abs(v) >= 100:
        return "%.0f" % v
    return "%.3g" % v


def row(name, a, b, min_pct):
    delta, label, p = verdict(a, b, min_pct)
    sd_a = (a["sd"] / a["mean"] * 100.0) if a and a.get("mean") else 0.0
    sd_b = (b["sd"] / b["mean"] * 100.0) if b and b.get("mean") else 0.0
    return "%-46s %9s +-%4.1f%% %9s +-%4.1f%% %8s  %-7s p=%.3f" % (
        name[:46], fmt(a.get("median")) if a else "-", sd_a, fmt(b.get("median")) if b else "-", sd_b,
        ("%+.1f%%" % delta) if delta is not None else "-", label, p)


def compare(base, new, show_all, min_pct):
    lines = []
    lines.append("base: %s (%s, %s reps, flags %s)   new: %s (%s, %s reps, flags %s)" % (
        base["sha"], base["mode"], base["reps"], base["flags"], new["sha"], new["mode"], new["reps"], new["flags"]))
    bs, ns = base.get("score", {}), new.get("score", {})
    if not new["gates"]["ok"]:
        lines.append("!! NEW RUN FAILED ITS CORRECTNESS GATES: " + "; ".join(f[:200] for f in new["gates"]["failures"][:5]))
    lines.append("")
    header = "%-46s %16s %16s %8s  %-7s" % ("metric (median, +-cv)", "base", "new", "delta", "verdict")
    lines.append(header)
    lines.append("-" * len(header))
    # recompute score of both against the base as reference
    ref = {"components": components(base), "sha": base["sha"]}
    sb, sn = score(base, ref), score(new, ref)
    lines.append("SCORE (base = 100)   base %.1f   new %.1f   (macro x%.3f, micro x%.3f)%s" % (
        sb["value"], sn["value"], sn["macroRatio"], sn["microRatio"], "" if sn["valid"] else "   INVALID (gates failed)"))
    lines.append("")
    for scenario in sorted(set(base["macro"]) & set(new["macro"])):
        lines.append("[macro %s]  scenario ratio %.3f" % (scenario, sn["perScenario"].get(scenario, {}).get("ratio", float("nan"))))
        keys = KEY_MACRO if not show_all else sorted(set(base["macro"][scenario]) & set(new["macro"][scenario]))
        for k in keys:
            a, b = base["macro"][scenario].get(k), new["macro"][scenario].get(k)
            if a and b:
                lines.append(row("  " + k, a, b, min_pct))
    for scale in sorted(set(base["micro"]) & set(new["micro"]), key=int):
        lines.append("[micro %s open requests]" % scale)
        keys = sorted(k for k in base["micro"][scale] if k in new["micro"][scale] and
                      (show_all or (k.split(".")[-1] in ("cpuNs", "bytes", "netOfIdleTicksCpuNs") and k.startswith("ops."))))
        for k in keys:
            lines.append(row("  " + k[4:] if k.startswith("ops.") else "  " + k, base["micro"][scale][k], new["micro"][scale][k], min_pct))
    return "\n".join(lines)


def show(result):
    s = result["score"]
    print("%s  mode=%s reps=%s flags=%s  gates=%s" % (result["sha"], result["mode"], result["reps"], result["flags"],
                                                     "OK" if result["gates"]["ok"] else "FAILED"))
    for f in result["gates"]["failures"][:10]:
        print("  gate failure:", f[:400].replace("\n", " | "))
    for f in result["gates"].get("hangs", [])[:10]:
        print("  hang:", f[:300])
    print("score %.1f (macro x%.3f micro x%.3f, reference %s)%s" % (s["value"], s["macroRatio"], s["microRatio"], s["reference"],
                                                                 "" if s["valid"] else "  INVALID"))
    for scenario, metrics in sorted(result["macro"].items()):
        print("[macro %s]" % scenario)
        for k in KEY_MACRO:
            if k in metrics and metrics[k]["n"]:
                st = metrics[k]
                print("  %-34s mean %10s  sd %5.1f%%  min %9s max %9s" % (k, fmt(st["mean"]), st["cv"] * 100, fmt(st["min"]), fmt(st["max"])))
    for scale in sorted(result["micro"], key=int):
        print("[micro %s]" % scale)
        for k, st in sorted(result["micro"][scale].items()):
            if k.startswith("ops.") and k.split(".")[-1] in ("cpuNs", "bytes", "netOfIdleTicksCpuNs") and st["n"]:
                print("  %-34s mean %10s  sd %5.1f%%" % (k[4:], fmt(st["mean"]), st["cv"] * 100))


# ---------------------------------------------------------------------------------------------------------- main

def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == "merge":
        raw = argv[2]
        opts = {}
        i = 3
        while i < len(argv):
            opts[argv[i].lstrip("-")] = argv[i + 1]
            i += 2
        meta = {"sha": opts.get("sha", "?"), "mode": opts.get("mode", "?"), "reps": int(opts.get("reps", 0)),
                "flags": opts.get("flags", "?"), "seed": int(opts.get("seed", 0)), "status": int(opts.get("status", 0))}
        here = os.path.dirname(os.path.abspath(__file__))
        # quick runs have smaller scenarios: their score is relative to their own reference
        ref_path = opts.get("ref") or os.path.join(here, "rs_bench_ref_quick.json" if meta["mode"] == "quick" else "rs_bench_ref.json")
        result = merge(raw, meta, ref_path)
        out = opts["out"]
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w") as f:
            json.dump(result, f, indent=1)
        show(result)
        print("wrote", out)
        return 0 if result["gates"]["ok"] else 1
    if cmd == "compare":
        paths = [a for a in argv[2:] if not a.startswith("--")]
        show_all = "--all" in argv
        min_pct = 3.0
        if "--min-pct" in argv:
            min_pct = float(argv[argv.index("--min-pct") + 1])
            paths = [p for p in paths if p != argv[argv.index("--min-pct") + 1]]
        with open(paths[0]) as f:
            base = json.load(f)
        with open(paths[1]) as f:
            new = json.load(f)
        print(compare(base, new, show_all, min_pct))
        return 0
    if cmd == "show":
        with open(argv[2]) as f:
            show(json.load(f))
        return 0
    if cmd == "ref":
        with open(argv[2]) as f:
            result = json.load(f)
        with open(argv[3], "w") as f:
            json.dump({"sha": result["sha"], "components": components(result)}, f, indent=1)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
