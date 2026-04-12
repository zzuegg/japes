package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;
import zzuegg.ecs.system.SystemInvoker;

import java.util.*;

public final class ScheduleGraph {

    public static final class SystemNode {
        private final SystemDescriptor descriptor;
        // Index into the owning ScheduleGraph.nodes list; set once during construction
        // so ScheduleGraph.complete(node) can do an O(1) lookup instead of nodes.indexOf.
        private int index = -1;
        private SystemInvoker invoker;

        public SystemNode(SystemDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        public SystemDescriptor descriptor() { return descriptor; }
        public SystemInvoker invoker() { return invoker; }
        public void setInvoker(SystemInvoker invoker) { this.invoker = invoker; }
        public int index() { return index; }
        void setIndex(int index) { this.index = index; }
    }

    private final List<SystemNode> nodes;
    private final Map<Integer, Set<Integer>> edges;
    private final int[] originalInDegree;
    private final int[] inDegree;
    private final boolean[] completed;

    ScheduleGraph(List<SystemNode> nodes, Map<Integer, Set<Integer>> edges, int[] inDegree) {
        this.nodes = nodes;
        this.edges = edges;
        this.originalInDegree = Arrays.copyOf(inDegree, inDegree.length);
        this.inDegree = Arrays.copyOf(inDegree, inDegree.length);
        this.completed = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).setIndex(i);
        }
    }

    public List<SystemNode> readySystems() {
        var ready = new ArrayList<SystemNode>();
        for (int i = 0; i < nodes.size(); i++) {
            if (!completed[i] && inDegree[i] == 0) {
                ready.add(nodes.get(i));
            }
        }
        return ready;
    }

    public void complete(SystemNode node) {
        int idx = node.index();
        if (idx < 0 || idx >= nodes.size() || nodes.get(idx) != node) {
            throw new IllegalArgumentException("Unknown node: " + node.descriptor().name());
        }
        completed[idx] = true;

        var deps = edges.getOrDefault(idx, Set.of());
        for (int dep : deps) {
            inDegree[dep]--;
        }
    }

    public boolean isComplete() {
        for (boolean c : completed) {
            if (!c) return false;
        }
        return true;
    }

    public List<SystemNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public void reset() {
        java.lang.System.arraycopy(originalInDegree, 0, inDegree, 0, inDegree.length);
        Arrays.fill(completed, false);
    }

    /**
     * Pre-computed topological order — built once lazily, cached forever.
     * The single-threaded executor iterates this flat array instead of
     * running the readySystems/complete DAG loop on every tick, saving
     * per-tick HashMap lookups, ArrayList allocations, and boolean scans.
     */
    private SystemNode[] cachedFlatOrder;

    public SystemNode[] flatOrder() {
        if (cachedFlatOrder != null) return cachedFlatOrder;
        // Kahn's algorithm — same topological sort readySystems/complete
        // does, but executed once at build time.
        int n = nodes.size();
        var tempInDeg = Arrays.copyOf(originalInDegree, n);
        var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < n; i++) {
            if (tempInDeg[i] == 0) queue.add(i);
        }
        var result = new SystemNode[n];
        int write = 0;
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            result[write++] = nodes.get(idx);
            for (int dep : edges.getOrDefault(idx, Set.of())) {
                tempInDeg[dep]--;
                if (tempInDeg[dep] == 0) queue.add(dep);
            }
        }
        if (write != n) {
            throw new IllegalStateException("Deadlock: cycle in schedule DAG — only " + write + " of " + n + " systems reachable");
        }
        cachedFlatOrder = result;
        return cachedFlatOrder;
    }

    public void buildInvokers() {
        for (var node : nodes) {
            if (node.descriptor().method() != null && node.invoker() == null) {
                node.setInvoker(SystemInvoker.create(node.descriptor()));
            }
        }
    }
}
