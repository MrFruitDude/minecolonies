#!/usr/bin/env python3
"""Summarise a JFR recording of a benchmark run: the hottest request-system methods by CPU and by allocation.

  python3 -I tools/rs_bench_jfr.py RECORDING.jfr [--top 10] [--json OUT.json] [--under Class.method]

--under: instead of the three tables, break down the CPU samples that have Class.method on their stack by the frame directly below it
(who it calls, with line numbers for its own code), as a share of those samples.

A sample counts as request-system work when any frame of its stack is in a request-system class (see RS_MARKERS). It is
attributed to the innermost such frame ("owner": the RS method that was running or called the leaf, e.g. a HashMap lookup
made from ReservationLedger.sum is charged to ReservationLedger.sum) and, for the inclusive view, to every RS method on
its stack. Percentages are of all server-thread samples; "of RS" is relative to the request-system samples only.
Allocation uses jdk.ObjectAllocationSample (weighted by the sampled bytes).
"""
import collections
import json
import re
import subprocess
import sys

RS_MARKERS = (
    "com.minecolonies.core.colony.requestsystem.",
    "com.minecolonies.api.colony.requestsystem.",
    "com.minecolonies.core.colony.jobs.JobDeliveryman",
    "com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman",
    "com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule",
    "com.minecolonies.core.colony.buildings.modules.MinimumStockModule",
    "com.minecolonies.core.colony.managers.ColonyPackageManager.sendRequestSystemPackets",
)
SERVER_THREAD = "Server thread"


def is_rs(frame):
    return any(frame.startswith(m) for m in RS_MARKERS)


def method_of(frame):
    # "com.x.Y.method(Args) line: 12" -> "Y.method"
    head = frame.split("(")[0]
    parts = head.split(".")
    return ".".join(parts[-2:])


def events(path, kind):
    out = subprocess.run(["jfr", "print", "--events", kind, "--stack-depth", "48", path], capture_output=True, text=True, check=True).stdout
    cur = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith(kind):
            cur = {"frames": [], "thread": None, "weight": 0}
        elif cur is None:
            continue
        elif s.startswith("sampledThread") or s.startswith("eventThread"):
            m = re.search(r'"([^"]+)"', s)
            cur["thread"] = m.group(1) if m else ""
        elif s.startswith("weight"):
            m = re.search(r"=\s*([\d.]+)\s*(\w+)", s)
            if m:
                v, unit = float(m.group(1)), m.group(2)
                cur["weight"] = v * {"bytes": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}.get(unit, 1)
        elif s.startswith("stackTrace"):
            cur["in_stack"] = True
        elif s == "}":
            if "in_stack" in cur:
                yield cur
            cur = None
        elif cur.get("in_stack") and s not in ("]", "..."):
            cur["frames"].append(s)


def summarise(path, kind, weighted):
    total, rs_total = 0.0, 0.0
    owner = collections.Counter()
    leaf = collections.Counter()
    inclusive = collections.Counter()
    for ev in events(path, kind):
        if ev["thread"] != SERVER_THREAD or not ev["frames"]:
            continue
        w = ev["weight"] if weighted and ev["weight"] else 1.0
        total += w
        rs_frames = [f for f in ev["frames"] if is_rs(f)]
        if not rs_frames:
            continue
        rs_total += w
        owner[method_of(rs_frames[0])] += w
        leaf[method_of(ev["frames"][0])] += w
        for m in {method_of(f) for f in rs_frames}:
            inclusive[m] += w
    return total, rs_total, owner, leaf, inclusive


def table(title, total, rs_total, counter, top, unit):
    rows = []
    print("%s  (request-system share of server-thread %s: %.1f%%)" % (title, unit, 100.0 * rs_total / total if total else 0))
    for name, w in counter.most_common(top):
        rows.append({"method": name, "pctOfServer": 100.0 * w / total if total else 0, "pctOfRs": 100.0 * w / rs_total if rs_total else 0})
        print("  %5.1f%% of server  %5.1f%% of RS   %s" % (rows[-1]["pctOfServer"], rows[-1]["pctOfRs"], name))
    return rows


def under(path, target, top):
    total = 0.0
    parts = collections.Counter()
    for ev in events(path, "jdk.ExecutionSample"):
        if not ev["frames"]:
            continue
        idx = next((i for i, f in enumerate(ev["frames"]) if method_of(f) == target), None)
        if idx is None:
            continue
        total += 1
        if idx == 0:
            parts["(self) " + ev["frames"][0]] += 1
        else:
            callee = ev["frames"][idx - 1]
            m = re.search(r"line:\s*(\d+)", ev["frames"][idx])
            site = " (called at line %s)" % m.group(1) if m else ""
            parts[method_of(callee) + site + ("" if is_rs(callee) else "  <- first non-RS callee")] += 1
    print("%d samples have %s on the stack; direct callees:" % (total, target))
    for name, w in parts.most_common(top):
        print("  %5.1f%%  %s" % (100.0 * w / total if total else 0, name))
    return 0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[1]
    top = int(argv[argv.index("--top") + 1]) if "--top" in argv else 10
    if "--under" in argv:
        return under(path, argv[argv.index("--under") + 1], top)
    result = {}
    total, rs_total, owner, leaf, inclusive = summarise(path, "jdk.ExecutionSample", False)
    result["cpu"] = {
        "samples": total, "rsSamples": rs_total,
        "owner": table("CPU, by innermost request-system method", total, rs_total, owner, top, "CPU samples"),
        "inclusive": table("CPU, inclusive (any request-system frame on the stack)", total, rs_total, inclusive, top, "CPU samples"),
        "leaf": table("CPU, by leaf frame (inside request-system stacks)", total, rs_total, leaf, top, "CPU samples"),
    }
    total, rs_total, owner, leaf, inclusive = summarise(path, "jdk.ObjectAllocationSample", True)
    result["alloc"] = {
        "bytes": total, "rsBytes": rs_total,
        "owner": table("Allocation, by innermost request-system method", total, rs_total, owner, top, "allocated bytes"),
        "leaf": table("Allocation, by allocating frame (inside request-system stacks)", total, rs_total, leaf, top, "allocated bytes"),
    }
    if "--json" in argv:
        with open(argv[argv.index("--json") + 1], "w") as f:
            json.dump(result, f, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
