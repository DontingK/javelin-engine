package com.javelin.core.obj;


import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ObjectCache {

    private final static Map<Integer, ObjectRef> cache = new ConcurrentHashMap<>();

    private final static ReferenceQueue<ObjectId> refQueue = new ReferenceQueue<>();

    static void add(ObjectId objectId) {
        ObjectRef objectRef = new ObjectRef(objectId, refQueue);
        cache.put(objectId.id, objectRef);
    }

    public static <T extends ObjectId> T get(int id) {
        ObjectRef objectRef = cache.get(id);
        if (objectRef != null) {
            return (T) objectRef.get();
        }
        return null;
    }

    public void update() {
        Reference<? extends ObjectId> poll = refQueue.poll();
        while (poll != null) {
            if (poll instanceof ObjectRef) {
                cache.remove(((ObjectRef) poll).id);
            }
            poll = refQueue.poll();
        }
    }

    private static class ObjectRef extends WeakReference<ObjectId> {
        private final int id;

        public ObjectRef(ObjectId objectId, ReferenceQueue<ObjectId> queue) {
            super(objectId, queue);
            this.id = objectId.getId();
        }
    }
}
