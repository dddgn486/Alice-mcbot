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
            if (node.combinedCost >= parent.combinedCost) {
                break;
            }
            heap[idx] = parent;
            parent.heapIndex = idx;
            idx = parentIdx;
        }
        heap[idx] = node;
        node.heapIndex = idx;
    }

    private void siftDown(PathNode node) {
        int idx = node.heapIndex;
        int half = size / 2;
        while (idx < half) {
            int leftIdx = idx * 2 + 1;
            int rightIdx = leftIdx + 1;
            int bestIdx = rightIdx < size && heap[rightIdx].combinedCost < heap[leftIdx].combinedCost
                    ? rightIdx : leftIdx;
            PathNode bestChild = heap[bestIdx];
            if (node.combinedCost <= bestChild.combinedCost) {
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
