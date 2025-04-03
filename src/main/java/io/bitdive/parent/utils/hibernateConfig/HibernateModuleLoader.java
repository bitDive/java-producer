package io.bitdive.parent.utils.hibernateConfig;

import com.fasterxml.jackson.databind.Module;

public class HibernateModuleLoader {
    public static Module registerHibernateModule() {
        try {
            Integer majorVersion = HibernateVersionDetector.getHibernateMajorVersion();
            if (majorVersion == null) {
                return null;
            }

            if (loadTransientAnnotation(majorVersion) == null) {
                return null;
            }

            String moduleClassName = (majorVersion >= 6)
                    ? "com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module"
                    : "com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module";
            Class<?> moduleClass = Class.forName(moduleClassName);
            return (com.fasterxml.jackson.databind.Module) moduleClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    public static Class<?> loadTransientAnnotation(int hibernateMajorVersion) {
        String className = (hibernateMajorVersion >= 6)
                ? "jakarta.persistence.Transient"
                : "javax.persistence.Transient";
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
