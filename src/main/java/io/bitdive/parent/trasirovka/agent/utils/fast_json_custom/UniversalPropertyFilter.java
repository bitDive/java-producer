package io.bitdive.parent.trasirovka.agent.utils.fast_json_custom;

import com.alibaba.fastjson.serializer.PropertyFilter;

public class UniversalPropertyFilter implements PropertyFilter {
    @Override
    public boolean apply(Object object, String name, Object value) {
        if (value == null) {
            return true;
        }
        if (isClassInHierarchy(value.getClass(), "org.hibernate.proxy.HibernateProxy") ||
                isClassInHierarchy(value.getClass(), "org.hibernate.collection.spi.PersistentCollection")) {
            return false;
        }
        return true;
    }

    private boolean isClassInHierarchy(Class<?> clazz, String targetClassName) {
        while (clazz != null) {
            if (clazz.getName().equals(targetClassName)) {
                return true;
            }
            // Check interfaces
            for (Class<?> iface : clazz.getInterfaces()) {
                if (iface.getName().equals(targetClassName) || isClassInHierarchy(iface, targetClassName)) {
                    return true;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
}
