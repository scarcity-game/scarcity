package com.pessimisticit.scarcity.resourcemodel;

import java.util.List;

/** One page of results: {@code {items,page,size,total}}; page is 0-based, total counts all matches. */
public record Page<T>(List<T> items, int page, int size, long total) {
}
