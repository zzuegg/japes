package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;
import zzuegg.ecs.system.SystemInvoker;

import java.util.*;

public final class ScheduleGraph {

    public static final class SystemNode {
        private final SystemDescriptor descriptor;
        private SystemInvoker invoker;

        public SystemNode(SystemDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        public SystemDescriptor descriptor() { return descriptor; }
        public SystemInvoker invoker() { return invoker; }
        public void setInvoker(SystemInvoker invoker) { this.invoker = invoker; }
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
        int idx = nodes.indexOf(node);
        if (idx < 0) throw new IllegalArgumentException("Unknown node: " + node.descriptor().name());
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

    public void buildInvokers() {
        for (var node : nodes) {
            if (node.descriptor().method() != null && node.invoker() == null) {
                node.setInvoker(SystemInvoker.create(node.descriptor()));
            }
        }
    }
}
