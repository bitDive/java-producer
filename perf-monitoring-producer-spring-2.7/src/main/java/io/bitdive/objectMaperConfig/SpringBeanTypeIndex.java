package io.bitdive.objectMaperConfig;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.concurrent.ConcurrentHashMap;

public final class SpringBeanTypeIndex {

    private static volatile ListableBeanFactory beanFactory;
    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private SpringBeanTypeIndex() {}

    public static void init(ListableBeanFactory bf) {
        beanFactory = bf;
        CACHE.clear();
    }

    public static boolean isBeanType(Class<?> type) {
        ListableBeanFactory bf = beanFactory;
        if (bf == null || type == null) return false;

        return CACHE.computeIfAbsent(type, t -> {
            try {
                String[] names = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(bf, t, true, false);
                return names != null && names.length > 0;
            } catch (Throwable e) {
                // лучше не падать
                return false;
            }
        });
    }
}
