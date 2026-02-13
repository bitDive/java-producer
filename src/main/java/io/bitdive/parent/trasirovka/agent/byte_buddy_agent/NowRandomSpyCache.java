package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;

public class NowRandomSpyCache {

    public static volatile Object CONTEXT_MANAGER_METHOD_CACHE;

    public static volatile Object MESSAGE_SEND_METHOD_CACHE;

    public static final ThreadLocal<Boolean> IN_ADVICE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** null = ещё не инициализировали, String[] = результат (загружаем один раз при первом вызове) */
    public static volatile String[] ALLOW_PREFIXES = null;

    /**
     * Загружает packedScanner из YAML один раз. Вызывать при первом обращении к prefixes.
     * Возвращает пустой массив при ошибке/отсутствии конфига.
     */
    public static String[] loadAllowPrefixesOnce() {
        try {

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            Class<?> ypc = Class.forName("io.bitdive.parent.parserConfig.YamlParserConfig", true, cl);

            Method mPc = ypc.getMethod("getProfilingConfig");
            Object profilingConfig;
            try { profilingConfig = MethodHandles.publicLookup().unreflect(mPc).invoke(); }
            catch (Throwable e) { mPc.setAccessible(true); profilingConfig = mPc.invoke(null); }
            if (profilingConfig == null) return new String[0];

            Method mApp = profilingConfig.getClass().getMethod("getApplication");
            Object app;
            try { app = MethodHandles.publicLookup().unreflect(mApp).invoke(profilingConfig); }
            catch (Throwable e) { mApp.setAccessible(true); app = mApp.invoke(profilingConfig); }
            if (app == null) return new String[0];

            Method mPacked = app.getClass().getMethod("getPackedScanner");
            Object packedScanner;
            try { packedScanner = MethodHandles.publicLookup().unreflect(mPacked).invoke(app); }
            catch (Throwable e) { mPacked.setAccessible(true); packedScanner = mPacked.invoke(app); }
            if (packedScanner == null) return new String[0];

            ArrayList<String> out = new ArrayList<>();
            if (packedScanner instanceof String) {
                String s = ((String) packedScanner).trim();
                if (!s.isEmpty()) {
                    for (String p : s.split("[,;\\s]+")) addPrefix(out, p);
                }
            } else if (packedScanner instanceof String[]) {
                for (String p : (String[]) packedScanner) addPrefix(out, p);
            } else if (packedScanner instanceof Collection) {
                for (Object o : (Collection<?>) packedScanner) addPrefix(out, o != null ? String.valueOf(o) : null);
            } else {
                addPrefix(out, String.valueOf(packedScanner));
            }
            return out.isEmpty() ? new String[0] : out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static void addPrefix(ArrayList<String> out, String p) {
        if (p == null) return;
        p = p.trim();
        if (p.isEmpty()) return;
        if (!p.endsWith(".")) p = p + ".";
        out.add(p);
    }

}
