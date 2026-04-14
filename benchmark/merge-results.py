#!/usr/bin/env python3
"""
Merge JMH JSON + Criterion JSON results from all benchmark modules into
a single site/data/benchmark-results.json file.

Each entry has:
  - benchmark: full benchmark name
  - name: short method name (e.g., "iterateWithWrite")
  - implementation: which ECS library (japes, bevy, zayes, artemis, dominion)
  - params: dict of parameter values
  - mode: benchmark mode (thrpt, avgt, etc.)
  - us_per_op: microseconds per operation
  - bytes_per_op: heap allocation per operation (JMH gc profiler; null for Criterion)
  - ops_per_ms: operations per millisecond
  - entities_per_second: entity throughput (ops/s × entity count)
  - score: raw score value
  - score_unit: raw unit
  - score_error: confidence interval error
"""

import json
import glob
import os
import re
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

# ---------------------------------------------------------------
# JMH processing
# ---------------------------------------------------------------

def extract_gc_alloc(entry):
    secondary = entry.get("secondaryMetrics", {})
    gc = secondary.get("gc.alloc.rate.norm", {})
    return gc.get("score", None) if gc else None

def jmh_to_us(score, unit, mode):
    if mode == "thrpt":
        if "ms" in unit: return 1000.0 / score if score > 0 else None
        if "s" in unit:  return 1_000_000.0 / score if score > 0 else None
    elif mode in ("avgt", "sample", "ss"):
        if "us" in unit: return score
        if "ms" in unit: return score * 1000.0
        if "ns" in unit: return score / 1000.0
        if "s" in unit:  return score * 1_000_000.0
        return score
    return None

def jmh_to_ops_ms(score, unit, mode):
    if mode == "thrpt":
        if "ms" in unit: return score
        if "s" in unit:  return score / 1000.0
        return score
    elif mode in ("avgt", "sample", "ss"):
        if "us" in unit: return 1000.0 / score if score > 0 else None
        if "ms" in unit: return 1.0 / score if score > 0 else None
        if "ns" in unit: return 1_000_000.0 / score if score > 0 else None
    return None

def short_name(benchmark):
    return benchmark.rsplit(".", 1)[-1] if "." in benchmark else benchmark

def entity_count_from_params(params):
    for key in ("entityCount", "bodyCount"):
        if key in params:
            try: return int(params[key])
            except ValueError: pass
    if "predators" in params and "preyPerPredator" in params:
        try: return int(params["predators"]) * int(params["preyPerPredator"])
        except ValueError: pass
    return None

def process_jmh_file(filepath, implementation):
    with open(filepath) as f:
        data = json.load(f)
    results = []
    for entry in data:
        benchmark = entry.get("benchmark", "")
        if ":gc." in benchmark:
            continue
        mode = entry.get("mode", "")
        score = entry.get("primaryMetric", {}).get("score", 0)
        score_error = entry.get("primaryMetric", {}).get("scoreError", 0)
        score_unit = entry.get("primaryMetric", {}).get("scoreUnit", "")
        params = entry.get("params", {})

        us = jmh_to_us(score, score_unit, mode)
        ops = jmh_to_ops_ms(score, score_unit, mode)
        alloc = extract_gc_alloc(entry)
        ec = entity_count_from_params(params)
        eps = round(ops * 1000.0 * ec) if ops and ec else None

        results.append({
            "benchmark": benchmark,
            "name": short_name(benchmark),
            "implementation": implementation,
            "params": params,
            "mode": mode,
            "us_per_op": round(us, 3) if us is not None else None,
            "ops_per_ms": round(ops, 3) if ops is not None else None,
            "entities_per_second": eps,
            "bytes_per_op": round(alloc, 1) if alloc is not None else None,
            "score": round(float(score), 6) if score is not None else None,
            "score_unit": score_unit,
            "score_error": round(float(score_error), 6) if score_error is not None else None,
        })
    return results

# ---------------------------------------------------------------
# Criterion (Bevy) processing
# ---------------------------------------------------------------

def parse_criterion_params(bench_path):
    """Extract entity count and other params from the Criterion directory path."""
    parts = bench_path.split("/")
    params = {}

    # Check for predator/prey pattern: pred_500_prey_2000
    for part in parts:
        m = re.match(r"pred_(\d+)_prey_(\d+)", part)
        if m:
            params["predators"] = m.group(1)
            params["preyPerPredator"] = m.group(2)
            return params

    # Last numeric segment is typically entity count
    for part in reversed(parts):
        if part.isdigit():
            params["entityCount"] = part
            return params

    return params

def criterion_bench_name(bench_path):
    """Convert Criterion path to a readable benchmark name."""
    # e.g., "iteration/read_write/1000" -> "read_write"
    # e.g., "nbody/one_tick/10000" -> "one_tick"
    # e.g., "predator_prey/naive_tick/pred_500_prey_2000" -> "naive_tick"
    parts = bench_path.split("/")
    # Skip the param segment (last part if numeric or pred_*)
    name_parts = []
    for part in parts:
        if part.isdigit() or re.match(r"pred_\d+_prey_\d+", part):
            continue
        name_parts.append(part)
    return "/".join(name_parts) if name_parts else bench_path

def process_criterion(bevy_dir):
    """Process Criterion benchmark results from the bevy-benchmark directory."""
    criterion_dir = os.path.join(bevy_dir, "target", "criterion")
    if not os.path.isdir(criterion_dir):
        return []

    results = []
    for estimates_file in sorted(glob.glob(
            os.path.join(criterion_dir, "**/new/estimates.json"), recursive=True)):
        # Path relative to criterion dir, minus "/new/estimates.json"
        rel = os.path.relpath(estimates_file, criterion_dir)
        bench_path = os.path.dirname(os.path.dirname(rel))  # strip "new/estimates.json"

        with open(estimates_file) as f:
            data = json.load(f)

        # Criterion reports in nanoseconds
        mean_ns = data.get("mean", {}).get("point_estimate", 0)
        std_err_ns = data.get("mean", {}).get("standard_error", 0)

        us = mean_ns / 1000.0
        ops_ms = 1_000_000.0 / mean_ns if mean_ns > 0 else None

        params = parse_criterion_params(bench_path)
        ec = entity_count_from_params(params)
        eps = round(ops_ms * 1000.0 * ec) if ops_ms and ec else None

        full_name = f"bevy.{bench_path.replace('/', '.')}"
        name = criterion_bench_name(bench_path)

        results.append({
            "benchmark": full_name,
            "name": name,
            "implementation": "bevy",
            "params": params,
            "mode": "avgt",
            "us_per_op": round(us, 3),
            "ops_per_ms": round(ops_ms, 3) if ops_ms else None,
            "entities_per_second": eps,
            "bytes_per_op": None,  # Criterion doesn't measure allocation
            "score": round(mean_ns, 3),
            "score_unit": "ns/op",
            "score_error": round(std_err_ns, 3),
        })

    return results

# ---------------------------------------------------------------
# Main
# ---------------------------------------------------------------

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    benchmark_dir = os.path.join(root, "benchmark")
    output_file = os.path.join(root, "site", "data", "benchmark-results.json")

    all_results = []
    found = False

    # JMH modules
    for module_dir in sorted(glob.glob(os.path.join(benchmark_dir, "ecs-benchmark*"))):
        module_name = os.path.basename(module_dir)
        implementation = IMPL_MAP.get(module_name, module_name)
        results_file = os.path.join(module_dir, "build", "results", "jmh", "results.json")
        if not os.path.exists(results_file):
            print(f"  SKIP {module_name}: no results.json found")
            continue
        found = True
        entries = process_jmh_file(results_file, implementation)
        print(f"  {module_name}: {len(entries)} benchmarks")
        all_results.extend(entries)

    # Criterion (Bevy)
    bevy_dir = os.path.join(benchmark_dir, "bevy-benchmark")
    if os.path.isdir(os.path.join(bevy_dir, "target", "criterion")):
        entries = process_criterion(bevy_dir)
        if entries:
            found = True
            print(f"  bevy-benchmark: {len(entries)} benchmarks")
            all_results.extend(entries)
    else:
        print("  SKIP bevy-benchmark: no Criterion results found")

    if not found:
        print("No results found. Run benchmarks first: ./gradlew benchmarkAll")
        sys.exit(1)

    all_results.sort(key=lambda x: (x["implementation"], x["benchmark"], str(x.get("params", {}))))

    output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "jdk": os.popen("java -version 2>&1 | head -1").read().strip(),
        "rust": os.popen("rustc --version 2>/dev/null || echo 'n/a'").read().strip(),
        "results": all_results,
    }

    os.makedirs(os.path.dirname(output_file), exist_ok=True)
    with open(output_file, "w") as f:
        json_str = json.dumps(output, indent=2)
        json_str = json_str.replace(": NaN", ": null")
        f.write(json_str)

    print(f"\nWrote {len(all_results)} benchmarks to {output_file}")

if __name__ == "__main__":
    main()
