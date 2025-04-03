package io.bitdive.parent.utils.hibernateConfig;

public class HibernateVersionDetector {
    public static Integer getHibernateMajorVersion() {
        try {
            Class<?> versionClass = Class.forName("org.hibernate.Version");
            String version = (String) versionClass.getDeclaredMethod("getVersionString").invoke(null);
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (Exception e) {
            return null;
        }
    }
}