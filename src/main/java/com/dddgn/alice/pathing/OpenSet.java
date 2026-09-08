package com.dddgn.alice.pathing;

/**
 * A* 二叉堆(移植自 Baritone 的 BinaryHeapOpenSet 思路)。
 */
final class OpenSet {

    private PathNode[] heap = new PathNode[256];
    private int size;

    void insert(PathNode node) {
        if (size >= heap.length) {
            PathNode[] bigger = new PathNode[heap.length * 2];
            System.arraycopy(heap, 0, bigger, 0, heap.length);
            heap = bigger;
        }
        node.heapIndex = size;
        heap[size] = node;
        size++;
        siftUp(node);
    }

    PathNode removeBest() {
        if (size == 0) {
            return null;
        }
        PathNode best = heap[0];
        size--;
        PathNode last = heap[size];
        heap[size] = null;
        if (size > 0) {
            heap[0] = last;
            last.heapIndex = 0;
            siftDown(last);
        }
        best.heapIndex = -1;
        return best;
    }

    /** 节点 combinedCost 下降后恢复堆序。 */
    void update(PathNode node) {
        siftUp(node);
    }

    boolean isEmpty() {
        return size == 0;
    }

    private void siftUp(PathNode node) {
        int idx = node.heapIndex;
        while (idx > 0) {
            int parentIdx = (idx - 1) / 2;
            PathNode parent = heap[parentIdx];
            if (compare(node, parent) >= 0) {
                break;
            }
            heap[idx] = parent;
            parent.heapIndex = idx;
            idx = parentIdx;
        }
        heap[idx] = node;
        node.heapIndex = idx;
    }

    private static int compare(PathNode left, PathNode right) {
        int cost = Double.compare(left.combinedCost, right.combinedCost);
        if (cost != 0) {
            return cost;
        }
        int turns = Integer.compare(left.turns, right.turns);
        if (turns != 0) {
            return turns;
        }
        int x = Integer.compare(left.pos.getX(), right.pos.getX());
        if (x != 0) {
            return x;
        }
        int y = Integer.compare(left.pos.getY(), right.pos.getY());
        if (y != 0) {
            return y;
        }
        return Integer.compare(left.pos.getZ(), right.pos.getZ());
    }

    private void siftDown(PathNode node) {
        int idx = node.heapIndex;
        int half = size / 2;
        while (idx < half) {
            int leftIdx = idx * 2 + 1;
            int rightIdx = leftIdx + 1;
            int bestIdx = rightIdx < size && compare(heap[rightIdx], heap[leftIdx]) < 0
                    ? rightIdx : leftIdx;
            PathNode bestChild = heap[bestIdx];
            if (compare(node, bestChild) <= 0) {
                break;
            }
            heap[idx] = bestChild;
            bestChild.heapIndex = idx;
            idx = bestIdx;
        }
        heap[idx] = node;
        node.heapIndex = idx;
    }
}
