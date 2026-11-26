package com.pessimisticit.scarcity.pathfinding;

import java.util.Arrays;

/**
 * A binary min-heap of the ints {@code 0..n-1}, keyed by a {@code double[]} the caller owns, with
 * decrease-key: {@link #push} again after lowering a key. Ties go to the lower int, so a search
 * over it is deterministic.
 */
final class IntHeap {

    private final double[] key;
    private final int[] heap;
    private final int[] slot;
    private int size;

    IntHeap(double[] key) {
        this.key = key;
        this.heap = new int[key.length];
        this.slot = new int[key.length];
        Arrays.fill(slot, -1);
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** Adds {@code v}, or moves it up after its key was lowered. */
    void push(int v) {
        int at = slot[v];
        if (at < 0) {
            at = size++;
            heap[at] = v;
            slot[v] = at;
        }
        up(at);
    }

    /** Removes and returns the int with the lowest key. */
    int pop() {
        int top = heap[0];
        slot[top] = -1;
        size--;
        if (size > 0) {
            heap[0] = heap[size];
            slot[heap[0]] = 0;
            down(0);
        }
        return top;
    }

    private boolean less(int a, int b) {
        return key[a] < key[b] || key[a] == key[b] && a < b;
    }

    private void up(int at) {
        int i = at;
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (!less(heap[i], heap[parent])) {
                return;
            }
            swap(i, parent);
            i = parent;
        }
    }

    private void down(int at) {
        int i = at;
        while (true) {
            int least = i;
            for (int c = 2 * i + 1; c <= 2 * i + 2 && c < size; c++) {
                if (less(heap[c], heap[least])) {
                    least = c;
                }
            }
            if (least == i) {
                return;
            }
            swap(i, least);
            i = least;
        }
    }

    private void swap(int i, int j) {
        int a = heap[i];
        heap[i] = heap[j];
        heap[j] = a;
        slot[heap[i]] = i;
        slot[heap[j]] = j;
    }
}
