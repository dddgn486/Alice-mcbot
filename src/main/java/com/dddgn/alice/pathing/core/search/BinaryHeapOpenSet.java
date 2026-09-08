package com.dddgn.alice.pathing.core.search;

import java.util.Arrays;

/**
 * 二叉最小堆开放集，按 {@code combinedCost} 排序，支持 decrease-key
 * （对照 Baritone `BinaryHeapOpenSet`）。
 */
public final class BinaryHeapOpenSet {
    private static final int INITIAL_CAPACITY = 1024;

    private SearchNode[] heap = new SearchNode[INITIAL_CAPACITY];
    private int size;

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void insert(SearchNode node) {
        if (node.isOpen()) {
            throw new IllegalStateException("node already in open set");
        }
        if (size >= heap.length) {
            heap = Arrays.copyOf(heap, heap.length * 2);
        }
        node.heapIndex = size;
        heap[size] = node;
        size++;
        node.open = true;
        upHeapify(node.heapIndex);
    }

    /** 节点成本下降后恢复堆序（decrease-key）。 */
    public void update(SearchNode node) {
        int index = node.heapIndex;
        if (index < 0 || index >= size) {
            throw new IllegalStateException("node not in open set");
        }
        upHeapify(index);
        downHeapify(node.heapIndex);
    }

    public SearchNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException("open set is empty");
        }
        SearchNode lowest = heap[0];
        lowest.open = false;
        lowest.heapIndex = -1;
        size--;
        if (size > 0) {
            heap[0] = heap[size];
            heap[size] = null;
            heap[0].heapIndex = 0;
            downHeapify(0);
        } else {
            heap[0] = null;
        }
        return lowest;
    }

    private void upHeapify(int index) {
        SearchNode node = heap[index];
        while (index > 0) {
            int parentIndex = (index - 1) >>> 1;
            SearchNode parent = heap[parentIndex];
            if (parent.combinedCost <= node.combinedCost) {
                break;
            }
            heap[index] = parent;
            parent.heapIndex = index;
            index = parentIndex;
        }
        heap[index] = node;
        node.heapIndex = index;
    }

    private void downHeapify(int index) {
        SearchNode node = heap[index];
        while (true) {
            int left = (index << 1) + 1;
            if (left >= size) {
                break;
            }
            int right = left + 1;
            int child = (right < size && heap[right].combinedCost < heap[left].combinedCost) ? right : left;
            if (heap[child].combinedCost >= node.combinedCost) {
                break;
            }
            heap[index] = heap[child];
            heap[child].heapIndex = index;
            index = child;
        }
        heap[index] = node;
        node.heapIndex = index;
    }
}
