package org.elasticsearch.search.slice;

@FunctionalInterface
public interface ExpiredCallback<E> {
    void expire(E val);
}