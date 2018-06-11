package org.elasticsearch.search.slice;

import java.util.HashSet;
import java.util.Iterator;

public class ExpiredCacheEntry<K> {
    private HashSet<Entry> buckets;
    private final Object lock = new Object();
    private Thread cleaner;
    private ExpiredCallback callback;

    public ExpiredCacheEntry(long expirationSecs, ExpiredCallback<Entry> callback) {
        buckets = new HashSet<>();
        this.callback = callback;
        cleaner = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(60000);
                    synchronized (lock) {
                        Iterator<Entry> iterator = buckets.iterator();
                        while (iterator.hasNext()) {
                            final Entry entry = iterator.next();
                            if (System.currentTimeMillis() - entry.getTimeMillis() > expirationSecs) {
                                iterator.remove();
                                if (this.callback != null) {
                                    this.callback.expire(entry);
                                }
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                throw new IllegalArgumentException(e);
            }
        });
        cleaner.setDaemon(true);
        cleaner.start();
    }

    public Entry get(K key) {
        assert key != null;
        synchronized (lock) {
            for (Entry entry : buckets) {
                if (entry.getIndex().equals(key)) {
                    return entry;
                }
            }
            return null;
        }
    }

    public void put(K key, Integer size) {
        synchronized (lock) {
            final Entry entry = get(key);
            if (entry == null) {
                buckets.add(new Entry(key, size));
            } else {
                entry.setHits(size);
            }
        }
    }

    public Entry update(K key, Integer size) {
        synchronized (lock) {
            Iterator<Entry> it = buckets.iterator();
            while (it.hasNext()) {
                Entry entry = it.next();
                if (entry.getIndex().equals(key)) {
                    entry.setTimeMillis(System.currentTimeMillis());
                    entry.setHits(size);
                    return entry;
                }
            }
            final Entry entry = new Entry(key, size);
            buckets.add(entry);
            return entry;
        }
    }

    public void clear() {
        synchronized (lock) {
            buckets.clear();
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            for (Entry entry : buckets) {
                if (entry.getIndex().equals(key)) {
                    entry.setTimeMillis(System.currentTimeMillis());
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (lock) {
            return buckets.size();
        }
    }

    public static class Entry<K> {
        private K index;
        private long timeMillis;
        private long hits;

        public Entry(K index, Integer size) {
            this.index = index;
            this.timeMillis = System.currentTimeMillis();
            this.hits = size;
        }

        public K getIndex() {
            return index;
        }

        public void setIndex(K index) {
            this.index = index;
        }

        public long getTimeMillis() {
            return timeMillis;
        }

        public void setTimeMillis(long timeMillis) {
            this.timeMillis = timeMillis;
        }

        public long getHits() {
            return hits;
        }

        public void setHits(long hits) {
            this.hits += hits;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Entry)) {
                return false;
            }
            final Entry entry = (Entry) obj;
            return this.index.equals(entry.index);
        }

        @Override
        public int hashCode() {
            return index.hashCode();
        }
    }
}
