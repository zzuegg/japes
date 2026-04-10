package zzuegg.ecs.scheduler;

import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;

public final class DagBuilder {

    private DagBuilder() {}

    public static ScheduleGraph build(List<SystemDescriptor> descriptors) {
        var nodes = new ArrayList<ScheduleGraph.SystemNode>();
        var nameToIndex = new HashMap<String, Integer>();

        for (int i = 0; i < descriptors.size(); i++) {
            var desc = descriptors.get(i);
            nodes.add(new ScheduleGraph.SystemNode(desc));
            nameToIndex.put(desc.name(), i);
            // Also register the simple method name for ordering references
            if (desc.name().contains(".")) {
                var simpleName = desc.name().substring(desc.name().lastIndexOf('.') + 1);
                nameToIndex.putIfAbsent(simpleName, i);
            }
        }

        int n = nodes.size();
        var edges = new HashMap<Integer, Set<Integer>>();
        var inDegree = new int[n];

        for (int i = 0; i < n; i++) {
            var desc = descriptors.get(i);
            for (var afterName : desc.after()) {
                var depIdx = nameToIndex.get(afterName);
                if (depIdx != null) {
                    addEdge(edges, inDegree, depIdx, i);
                }
            }
            for (var beforeName : desc.before()) {
                var depIdx = nameToIndex.get(beforeName);
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

    private static boolean hasConflict(SystemDescriptor a, SystemDescriptor b) {
        // Exclusive systems take the whole World — they must not run in parallel
        // with anything, regardless of declared component/resource access.
        if (a.isExclusive() || b.isExclusive()) {
            return true;
        }
        for (var accessA : a.componentAccesses()) {
            for (var accessB : b.componentAccesses()) {
                if (accessA.componentId().equals(accessB.componentId())) {
                    if (accessA.accessType() == AccessType.WRITE || accessB.accessType() == AccessType.WRITE) {
                        return true;
                    }
                }
            }
        }
        for (var res : a.resourceWrites()) {
            if (b.resourceReads().contains(res) || b.resourceWrites().contains(res)) return true;
        }
        for (var res : b.resourceWrites()) {
            if (a.resourceReads().contains(res) || a.resourceWrites().contains(res)) return true;
        }
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
