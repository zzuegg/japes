(function () {
  "use strict";

  const DATA_URL = "../data/benchmark-results.json";

  // Benchmark name mappings: JSON benchmark field → canonical key
  const NAME_MAP = {
    // japes JMH
    "IterationBenchmark.iterateSingleComponent": "iterateSingleComponent",
    "IterationBenchmark.iterateTwoComponents": "iterateTwoComponents",
    "IterationBenchmark.iterateWithWrite": "iterateWithWrite",
    "NBodyBenchmark.simulateOneTick": "nbody",
    "ParticleScenarioBenchmark.tick": "particle",
    "SparseDeltaBenchmark.tick": "sparseDelta",
    "RealisticTickBenchmark.tick": "realisticTick",
    "RealisticTickBenchmarkSoA.tick": "realisticTickSoA",
    "PredatorPreyBenchmark.tick": "predatorPrey_pair",
    "PredatorPreyForEachPairBenchmark.tick": "predatorPrey_forEach",
    "UnifiedDeltaBenchmark.tick": "unifiedDelta",
    // Bevy Criterion
    "iteration.single_read": "iterateSingleComponent",
    "iteration.two_read": "iterateTwoComponents",
    "iteration.read_write": "iterateWithWrite",
    "nbody.one_tick": "nbody",
    "scenario.particle_tick": "particle",
    "sparse_delta.observe_changed_health": "sparseDelta",
    "realistic_tick.tick": "realisticTick",
    "predator_prey.tick": "predatorPrey_bevy_naive",
    "predator_prey.naive_tick": "predatorPrey_bevy_naive",
    "predator_prey.optimized_tick": "predatorPrey_bevy_opt",
  };

  function canonicalName(benchmark) {
    for (const [pattern, name] of Object.entries(NAME_MAP)) {
      if (benchmark.includes(pattern)) return name;
    }
    return null;
  }

  function paramKey(r) {
    const p = r.params || {};
    if (p.entityCount) return p.entityCount;
    if (p.bodyCount) return p.bodyCount;
    // Bevy uses predators/preyPerPredator, japes uses predatorCount/preyCount
    const pred = p.predators || p.predatorCount || "";
    const prey = p.preyPerPredator || p.preyCount || "";
    if (pred && prey) return `${pred}x${prey}`;
    return "";
  }

  function fmt(v) {
    if (v == null) return "—";
    if (v < 0.01) return v.toFixed(3);
    if (v < 10) return v.toFixed(2);
    if (v < 100) return v.toFixed(1);
    return Math.round(v).toLocaleString();
  }

  function fmtB(v) {
    if (v == null) return "—";
    if (v < 1) return "**0**";
    return Math.round(v).toLocaleString();
  }

  function buildIndex(results) {
    // index[canonicalName][entityKey][impl] = { us_per_op, bytes_per_op }
    const idx = {};
    for (const r of results) {
      const cn = canonicalName(r.benchmark);
      if (!cn) continue;
      const ek = paramKey(r);
      if (!idx[cn]) idx[cn] = {};
      if (!idx[cn][ek]) idx[cn][ek] = {};
      idx[cn][ek][r.implementation] = r;
    }
    return idx;
  }

  function val(idx, name, ek, impl) {
    return idx[name]?.[ek]?.[impl]?.us_per_op ?? null;
  }

  function bop(idx, name, ek, impl) {
    return idx[name]?.[ek]?.[impl]?.bytes_per_op ?? null;
  }

  function boldMin(values) {
    const nums = values.map((v) => (typeof v === "number" ? v : Infinity));
    const min = Math.min(...nums);
    return values.map((v, i) => {
      const s = fmt(v);
      return nums[i] === min && min < Infinity ? `**${s}**` : s;
    });
  }

  function mdTable(headers, rows) {
    const sep = headers.map(() => "---");
    const lines = [
      "| " + headers.join(" | ") + " |",
      "| " + sep.join(" | ") + " |",
    ];
    for (const row of rows) {
      lines.push("| " + row.join(" | ") + " |");
    }
    return lines.join("\n");
  }

  function renderTable(el, md) {
    const lines = md.split("\n").filter((l) => l.startsWith("|"));
    if (lines.length < 2) {
      el.textContent = "No data";
      return;
    }
    const table = document.createElement("table");
    const thead = document.createElement("thead");
    const tbody = document.createElement("tbody");

    lines.forEach((line, i) => {
      if (i === 1) return; // separator
      const cells = line
        .split("|")
        .slice(1, -1)
        .map((c) => c.trim());
      const tr = document.createElement("tr");
      cells.forEach((cell) => {
        const td = document.createElement(i === 0 ? "th" : "td");
        // Handle **bold** and [links](url)
        let html = cell.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
        html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
        td.innerHTML = html;
        if (i > 0 && /^\d|^<strong>\d|^—/.test(html)) td.style.textAlign = "right";
        tr.appendChild(td);
      });
      (i === 0 ? thead : tbody).appendChild(tr);
    });
    table.appendChild(thead);
    table.appendChild(tbody);

    // Match mkdocs-material table structure exactly
    const innerDiv = document.createElement("div");
    innerDiv.className = "md-typeset__table";
    innerDiv.appendChild(table);
    const outerDiv = document.createElement("div");
    outerDiv.className = "md-typeset__scrollwrap";
    outerDiv.setAttribute("tabindex", "0");
    outerDiv.appendChild(innerDiv);
    el.innerHTML = "";
    el.appendChild(outerDiv);
  }

  async function main() {
    const loadingEl = document.getElementById("bench-loading");
    let data;
    try {
      const resp = await fetch(DATA_URL);
      data = await resp.json();
    } catch (e) {
      if (loadingEl) loadingEl.textContent = "Failed to load benchmark data.";
      return;
    }
    if (loadingEl) loadingEl.style.display = "none";

    const idx = buildIndex(data.results);
    const impls = ["japes", "bevy", "zayes", "dominion", "artemis"];

    // Iteration micros
    const iterEl = document.getElementById("table-iteration");
    if (iterEl) {
      const rows = [];
      for (const [name, label] of [
        ["iterateSingleComponent", "iterateSingleComponent"],
        ["iterateTwoComponents", "iterateTwoComponents"],
        ["iterateWithWrite", "iterateWithWrite"],
      ]) {
        for (const ek of ["10000", "100000"]) {
          const vals = impls.map((impl) => val(idx, name, ek, impl));
          if (vals.every((v) => v == null)) continue;
          const ekLabel = ek === "10000" ? "10k" : "100k";
          const bold = boldMin(vals);
          rows.push([`[${label}](iteration-micros.md)`, ekLabel, ...bold]);
        }
      }
      renderTable(
        iterEl,
        mdTable(
          ["Benchmark", "Entities", "**japes**", "Bevy", "Zay-ES", "Dominion", "Artemis"],
          rows
        )
      );
    }

    // Scenarios
    const scenEl = document.getElementById("table-scenarios");
    if (scenEl) {
      const scenRows = [];
      const scenarios = [
        ["nbody", "10000", "10k", "N-Body oneTick", "nbody.md"],
        ["particle", "10000", "10k", "ParticleScenario", "particle-scenario.md"],
        ["sparseDelta", "10000", "10k", "SparseDelta", "sparse-delta.md"],
        ["realisticTick", "10000", "10k st", "RealisticTick", "realistic-tick.md"],
        ["realisticTick", "100000", "100k st", "RealisticTick", "realistic-tick.md"],
      ];
      for (const [name, ek, ekLabel, label, link] of scenarios) {
        const vals = impls.map((impl) => val(idx, name, ek, impl));
        if (vals.every((v) => v == null)) continue;
        const bold = boldMin(vals);
        scenRows.push([`[${label}](${link})`, ekLabel, ...bold]);
      }
      renderTable(
        scenEl,
        mdTable(
          ["Benchmark", "Entities", "**japes**", "Bevy", "Zay-ES", "Dominion", "Artemis"],
          scenRows
        )
      );
    }

    // Relations
    const relEl = document.getElementById("table-relations");
    if (relEl) {
      const jPair = val(idx, "predatorPrey_pair", "500x2000", "japes");
      const jForEach = val(idx, "predatorPrey_forEach", "500x2000", "japes");
      const bNaive = val(idx, "predatorPrey_bevy_naive", "500x2000", "bevy");
      const bOpt = val(idx, "predatorPrey_bevy_opt", "500x2000", "bevy");
      const relRows = [
        ["[PredatorPrey `@Pair`](predator-prey.md)", "500×2000", fmt(jPair), fmt(bNaive), fmt(bOpt)],
        ["[PredatorPrey `@ForEachPair`](predator-prey.md)", "500×2000", fmt(jForEach), fmt(bNaive), fmt(bOpt)],
      ];
      renderTable(
        relEl,
        mdTable(["Benchmark", "Cell", "**japes**", "Bevy naive", "Bevy hand-rolled"], relRows)
      );
    }

    // Unified delta
    const udEl = document.getElementById("table-unified");
    if (udEl) {
      const udRows = [];
      for (const ek of ["10000", "100000"]) {
        const j = val(idx, "unifiedDelta", ek, "japes");
        const z = val(idx, "unifiedDelta", ek, "zayes");
        if (j == null && z == null) continue;
        const ekLabel = ek === "10000" ? "10k" : "100k";
        udRows.push([`[UnifiedDelta](unified-delta.md)`, ekLabel, fmt(j), fmt(z)]);
      }
      if (udRows.length > 0) {
        renderTable(
          udEl,
          mdTable(["Benchmark", "Entities", "**japes** (6 systems)", "Zay-ES (1 EntitySet)"], udRows)
        );
      } else {
        udEl.textContent = "No unified delta data available.";
      }
    }

    // Allocation
    const allocEl = document.getElementById("table-allocation");
    if (allocEl) {
      const allocBenches = [
        ["iterateWithWrite", "10000", "iterateWithWrite 10k"],
        ["nbody", "10000", "NBody oneTick 10k"],
        ["sparseDelta", "10000", "SparseDelta 10k"],
        ["realisticTick", "10000", "RealisticTick 10k"],
        ["predatorPrey_forEach", "500x2000", "PredatorPrey @ForEachPair"],
        ["particle", "10000", "ParticleScenario 10k"],
        ["unifiedDelta", "10000", "UnifiedDelta 10k"],
      ];
      const allocRows = [];
      for (const [name, ek, label] of allocBenches) {
        const b = bop(idx, name, ek, "japes");
        if (b == null) continue;
        allocRows.push([label, ek === "500x2000" ? "500×2k" : (parseInt(ek) / 1000) + "k", fmtB(b)]);
      }
      renderTable(allocEl, mdTable(["Benchmark", "Entities", "B/op"], allocRows));
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", main);
  } else {
    main();
  }
})();
