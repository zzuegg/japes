#!/usr/bin/env python3
"""
Merge JMH JSON results from all benchmark modules into a single
site/data/benchmark-results.json file.

Each entry has:
  - benchmark: full JMH benchmark name
  - name: short method name (e.g., "iterateWithWrite")
  - implementation: which ECS library (japes, zayes, artemis, dominion, sync)
  - params: dict of JMH @Param values
  - mode: JMH benchmark mode (thrpt, avgt, etc.)
  - us_per_op: microseconds per operation
  - bytes_per_op: heap allocation per operation (from gc profiler)
  - ops_per_ms: operations per millisecond (for throughput mode)
  - score: raw JMH score
  - score_unit: raw JMH unit
  - score_error: JMH confidence interval error
"""

import json
import glob
import os
import sys
from datetime import datetime, timezone

IMPL_MAP = {
    "ecs-benchmark": "japes",
    "ecs-benchmark-zayes": "zayes",
    "ecs-benchmark-artemis": "artemis",
    "ecs-benchmark-dominion": "dominion",
    "ecs-benchmark-sync": "japes-sync",
    "ecs-benchmark-valhalla": "japes-valhalla",
}

def extract_gc_alloc(entry):
    """Extract gc.alloc.rate.norm from secondary results."""
    secondary = entry.get("secondaryMetrics", {})
    gc = secondary.get("gc.alloc.rate.norm", {})
    if gc:
        return gc.get("score", None)
    return None

def to_us_per_op(score, unit, mode):
    """Convert JMH score to microseconds/op."""
    if mode == "thrpt":
        # score is ops/time_unit
        if "ms" in unit:
            return 1000.0 / score if score > 0 else None
        elif "s" in unit:
            return 1_000_000.0 / score if score > 0 else None
        return None
    elif mode in ("avgt", "sample", "ss"):
        # score is time_unit/op
        if "us" in unit:
            return score
        elif "ms" in unit:
            return score * 1000.0
        elif "ns" in unit:
            return score / 1000.0
        elif "s" in unit:
            return score * 1_000_000.0
        return score
    return None

def to_ops_per_ms(score, unit, mode):
    """Convert JMH score to ops/ms."""
    if mode == "thrpt":
        if "ms" in unit:
            return score
        elif "s" in unit:
            return score / 1000.0
        return score
    elif mode in ("avgt", "sample", "ss"):
        if "us" in unit:
            return 1000.0 / score if score > 0 else None
        elif "ms" in unit:
            return 1.0 / score if score > 0 else None
        elif "ns" in unit:
            return 1_000_000.0 / score if score > 0 else None
        return None
    return None

def short_name(benchmark):
    """Extract method name from full JMH benchmark name."""
    # e.g., "zzuegg.ecs.bench.micro.IterationBenchmark.iterateWithWrite" -> "iterateWithWrite"
    return benchmark.rsplit(".", 1)[-1] if "." in benchmark else benchmark

def process_file(filepath, implementation):
    """Process a single JMH JSON results file."""
    with open(filepath) as f:
        data = json.load(f)

    results = []
    for entry in data:
        benchmark = entry.get("benchmark", "")
        mode = entry.get("mode", "")
        score = entry.get("primaryMetric", {}).get("score", 0)
        score_error = entry.get("primaryMetric", {}).get("scoreError", 0)
        score_unit = entry.get("primaryMetric", {}).get("scoreUnit", "")
        params = entry.get("params", {})

        # Skip gc.* secondary-only entries
        if ":gc." in benchmark:
            continue

        us = to_us_per_op(score, score_unit, mode)
        ops = to_ops_per_ms(score, score_unit, mode)
        alloc = extract_gc_alloc(entry)

        # Compute entities/second from ops/ms × entity count
        entity_count = None
        for key in ("entityCount", "bodyCount"):
            if key in params:
                try:
                    entity_count = int(params[key])
                except ValueError:
                    pass
        # For predator/prey, use predators × prey
        if entity_count is None and "predators" in params and "preyPerPredator" in params:
            try:
                entity_count = int(params["predators"]) * int(params["preyPerPredator"])
            except ValueError:
                pass

        entities_per_sec = None
        if ops is not None and entity_count is not None and ops > 0:
            entities_per_sec = round(ops * 1000.0 * entity_count)

        results.append({
            "benchmark": benchmark,
            "name": short_name(benchmark),
            "implementation": implementation,
            "params": params,
            "mode": mode,
            "us_per_op": round(us, 3) if us is not None else None,
            "ops_per_ms": round(ops, 3) if ops is not None else None,
            "entities_per_second": entities_per_sec,
            "bytes_per_op": round(alloc, 1) if alloc is not None else None,
            "score": round(float(score), 6) if score is not None else None,
            "score_unit": score_unit,
            "score_error": round(float(score_error), 6) if score_error is not None else None,
        })

    return results

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    benchmark_dir = os.path.join(root, "benchmark")
    output_file = os.path.join(root, "site", "data", "benchmark-results.json")

    all_results = []
    found = False

    for module_dir in sorted(glob.glob(os.path.join(benchmark_dir, "ecs-benchmark*"))):
        module_name = os.path.basename(module_dir)
        implementation = IMPL_MAP.get(module_name, module_name)

        # JMH JSON output location
        results_file = os.path.join(module_dir, "build", "results", "jmh", "results.json")
        if not os.path.exists(results_file):
            print(f"  SKIP {module_name}: no results.json found")
            continue

        found = True
        entries = process_file(results_file, implementation)
        print(f"  {module_name}: {len(entries)} benchmarks")
        all_results.extend(entries)

    if not found:
        print("No JMH results found. Run benchmarks first: ./gradlew benchmarkAll")
        sys.exit(1)

    # Sort by implementation, then benchmark name
    all_results.sort(key=lambda x: (x["implementation"], x["benchmark"], str(x.get("params", {}))))

    output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "jdk": os.popen("java -version 2>&1 | head -1").read().strip(),
        "results": all_results,
    }

    os.makedirs(os.path.dirname(output_file), exist_ok=True)
    with open(output_file, "w") as f:
        # Replace NaN with null for valid JSON
        json_str = json.dumps(output, indent=2)
        json_str = json_str.replace(": NaN", ": null")
        f.write(json_str)

    print(f"\nWrote {len(all_results)} benchmarks to {output_file}")

if __name__ == "__main__":
    main()
