package io.bitdive.parent.trasirovka.agent.utils;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;

public class SQLUtils {

    public static String getSQLFromStatement(Object stmt) throws Exception {
        if (stmt instanceof PreparedStatement) {
            return getSQLFromPreparedStatement((PreparedStatement) stmt);
        } else if (stmt instanceof Statement) {
            // Для Statement SQL передается напрямую в методы execute
            // Можно инструментировать эти методы, чтобы захватить аргумент SQL
            return null; // Заглушка
        }
        return null;
    }

    private static String getSQLFromPreparedStatement(PreparedStatement stmt) throws Exception {
        // Используйте рефлексию для доступа к SQL
        Field sqlField = getFieldFromHierarchy(stmt.getClass(), "sql");
        if (sqlField != null) {
            sqlField.setAccessible(true);
            return (String) sqlField.get(stmt);
        } else {
            // Если поле 'sql' недоступно, попытайтесь получить SQL через toString()
            String stmtString = stmt.toString();
            return extractSQLFromString(stmtString);
        }
    }

    private static Field getFieldFromHierarchy(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static String extractSQLFromString(String stmtString) {
        // Реализуйте логику извлечения SQL из строки
        return stmtString;
    }

    public static String reconstructSQL(String sql, Map<Integer, Object> params) {
        if (sql == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int paramIndex = 1;
        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char c = sql.charAt(i);
            if (c == '?') {
                Object param = params.get(paramIndex++);
                sb.append(formatParameter(param));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String formatParameter(Object param) {
        if (param instanceof String || param instanceof java.util.Date) {
            return "'" + param + "'";
        } else if (param == null) {
            return "NULL";
        } else {
            return param.toString();
        }
    }
}

