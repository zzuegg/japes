package zzuegg.ecs.scheduler;

import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;

public final class DagBuilder {

    private DagBuilder() {}

    public static ScheduleGraph build(List<SystemDescriptor> descriptors) {
        var nodes = new ArrayList<ScheduleGraph.SystemNode>();
        // Qualified names resolve unambiguously.
        var nameToIndex = new HashMap<String, Integer>();
        // Simple (method-only) names may shadow across classes: track every index
        // that registers a given simple name so we can fail loudly at resolution
        // time instead of silently binding to the first-registered candidate.
        var simpleNameCandidates = new HashMap<String, List<Integer>>();

        for (int i = 0; i < descriptors.size(); i++) {
            var desc = descriptors.get(i);
            nodes.add(new ScheduleGraph.SystemNode(desc));
            nameToIndex.put(desc.name(), i);
            if (desc.name().contains(".")) {
                var simpleName = desc.name().substring(desc.name().lastIndexOf('.') + 1);
                simpleNameCandidates.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(i);
            }
        }

        int n = nodes.size();
        var edges = new HashMap<Integer, Set<Integer>>();
        var inDegree = new int[n];

        for (int i = 0; i < n; i++) {
            var desc = descriptors.get(i);
            for (var afterName : desc.after()) {
                var depIdx = resolveReference(afterName, nameToIndex, simpleNameCandidates,
                    descriptors, desc.name(), "after");
                if (depIdx != null) {
                    addEdge(edges, inDegree, depIdx, i);
                }
            }
            for (var beforeName : desc.before()) {
                var depIdx = resolveReference(beforeName, nameToIndex, simpleNameCandidates,
                    descriptors, desc.name(), "before");
                if (depIdx != null) {
                    addEdge(edges, inDegree, i, depIdx);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (hasConflict(descriptors.get(i), descriptors.get(j))) {
                    if (!hasPath(edges, j, i, n)) {
                        addEdge(edges, inDegree, i, j);
                    }
                }
            }
        }

        validateNoCycles(nodes, edges, inDegree);

        return new ScheduleGraph(nodes, edges, inDegree);
    }

    private static Integer resolveReference(
            String reference,
            Map<String, Integer> nameToIndex,
            Map<String, List<Integer>> simpleNameCandidates,
            List<SystemDescriptor> descriptors,
            String referrer,
            String direction) {
        // Qualified match wins unambiguously.
        var qualified = nameToIndex.get(reference);
        if (qualified != null) return qualified;

        // Fall back to simple-name resolution; throw if it would bind ambiguously.
        var candidates = simpleNameCandidates.get(reference);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "System '" + referrer + "' declares " + direction + " = \"" + reference
                    + "\" but no system with that name exists");
        }
        if (candidates.size() > 1) {
            var options = new ArrayList<String>();
            for (var idx : candidates) options.add(descriptors.get(idx).name());
            throw new IllegalStateException(
                "Ambiguous " + direction + " reference '" + reference + "' from "
                    + referrer + "; matches: " + options
                    + ". Use the qualified 'Class.method' form.");
        }
        return candidates.getFirst();
    }

    private static boolean hasConflict(SystemDescriptor a, SystemDescriptor b) {
        // Exclusive systems take the whole World — they must not run in parallel
        // with anything, regardless of declared component/resource access.
        if (a.isExclusive() || b.isExclusive()) {
            return true;
        }

        boolean sharedWrite = false;
        for (var accessA : a.componentAccesses()) {
            for (var accessB : b.componentAccesses()) {
                if (accessA.componentId().equals(accessB.componentId())) {
                    if (accessA.accessType() == AccessType.WRITE || accessB.accessType() == AccessType.WRITE) {
                        sharedWrite = true;
                        break;
                    }
                }
            }
            if (sharedWrite) break;
        }

        if (sharedWrite && disjointArchetypeSets(a, b)) {
            // A write-conflict on component X still lets the systems run in
            // parallel if their @With/@Without filters guarantee they operate
            // on disjoint archetype sets — no chunk is ever visible to both.
            sharedWrite = false;
        }

        if (sharedWrite) return true;

        for (var res : a.resourceWrites()) {
            if (b.resourceReads().contains(res) || b.resourceWrites().contains(res)) return true;
        }
        for (var res : b.resourceWrites()) {
            if (a.resourceReads().contains(res) || a.resourceWrites().contains(res)) return true;
        }
        return false;
    }

    /**
     * True if the archetype sets selected by a and b cannot overlap. An
     * archetype matches a system iff it contains every required component
     * (from @Read/@Write/@With) and contains no forbidden component (from
     * @Without). A component that one system requires and the other forbids
     * makes their match sets disjoint.
     */
    private static boolean disjointArchetypeSets(SystemDescriptor a, SystemDescriptor b) {
        var reqA = new HashSet<Class<?>>();
        for (var ca : a.componentAccesses()) reqA.add(ca.type());
        reqA.addAll(a.withFilters());
        var reqB = new HashSet<Class<?>>();
        for (var cb : b.componentAccesses()) reqB.add(cb.type());
        reqB.addAll(b.withFilters());

        for (var f : a.withoutFilters()) if (reqB.contains(f)) return true;
        for (var f : b.withoutFilters()) if (reqA.contains(f)) return true;
        return false;
    }

    private static void addEdge(Map<Integer, Set<Integer>> edges, int[] inDegree, int from, int to) {
        if (edges.computeIfAbsent(from, k -> new HashSet<>()).add(to)) {
            inDegree[to]++;
        }
    }

    private static boolean hasPath(Map<Integer, Set<Integer>> edges, int from, int to, int n) {
        var visited = new boolean[n];
        var queue = new ArrayDeque<Integer>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == to) return true;
            if (visited[current]) continue;
            visited[current] = true;
            for (int next : edges.getOrDefault(current, Set.of())) {
                queue.add(next);
            }
        }
        return false;
    }

    private static void validateNoCycles(
            List<ScheduleGraph.SystemNode> nodes,
            Map<Integer, Set<Integer>> edges,
            int[] inDegree) {
        int n = nodes.size();
        var deg = Arrays.copyOf(inDegree, n);
        var queue = new ArrayDeque<Integer>();

        for (int i = 0; i < n; i++) {
            if (deg[i] == 0) queue.add(i);
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            processed++;
            for (int dep : edges.getOrDefault(current, Set.of())) {
                deg[dep]--;
                if (deg[dep] == 0) queue.add(dep);
            }
        }

        if (processed < n) {
            throw new IllegalStateException("Cycle detected in system dependency graph");
        }
    }
}
