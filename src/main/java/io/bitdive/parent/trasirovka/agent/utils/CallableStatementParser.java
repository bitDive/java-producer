package io.bitdive.parent.trasirovka.agent.utils;

import java.lang.reflect.Field;
import java.sql.CallableStatement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallableStatementParser {


    private static final Pattern CALL_PATTERN =
            Pattern.compile("(?i)\\{\\s*call\\s+([^(]+)\\(([^)]*)\\)\\s*}");

    public static String getCallableSQL(CallableStatement stmt) {
        if (stmt == null) return "NULL";

        String sql = tryMSSQL(stmt);
        if (sql != null) return sql;

        sql = tryMySQL(stmt);
        if (sql != null) return sql;

        sql = tryOracle(stmt);
        if (sql != null) return sql;

        sql = tryPostgreSQL(stmt);
        if (sql != null) return sql;

        return parseByToString(stmt);
    }


    private static String tryMSSQL(CallableStatement stmt) {
        String className = stmt.getClass().getName();
        if (!className.startsWith("com.microsoft.sqlserver.jdbc")) {
            return null;
        }

        try {
            Object sqlObj = getObjectMSSQL(stmt, "preparedSQL");

            if (sqlObj == null) {
                sqlObj = getObjectMSSQL(stmt, "userSQL");
                if (sqlObj == null) return null;
            }

            String sqlString = sqlObj.toString();
            return parseCallString(sqlString);

        } catch (Exception e) {
            return null;
        }
    }

    private static Object getObjectMSSQL(CallableStatement stmt, String fieldName) throws IllegalAccessException {
        Field psField = findFieldRecursively(stmt.getClass(), fieldName);
        if (psField == null) {
            return null;
        }
        psField.setAccessible(true);

        Object sqlObj = psField.get(stmt);
        return sqlObj;
    }

    private static Field findFieldRecursively(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // Переходим к родителю
                current = current.getSuperclass();
            }
        }
        // Не нашли
        return null;
    }

    private static String tryMySQL(CallableStatement stmt) {
        String className = stmt.getClass().getName();
        if (!className.startsWith("com.mysql.cj.jdbc")) {
            return null;
        }
        try {
            Field procNameField = stmt.getClass().getDeclaredField("procName");
            procNameField.setAccessible(true);
            Object procNameObj = procNameField.get(stmt);
            String procName = (procNameObj != null) ? procNameObj.toString() : "UNKNOWN_PROCEDURE";


            String params = "";
            try {
                Field paramsField = stmt.getClass().getDeclaredField("parameters");
                paramsField.setAccessible(true);
                Object parametersObj = paramsField.get(stmt);
                params = (parametersObj != null) ? parametersObj.toString() : "";
            } catch (NoSuchFieldException ignored) {

            }

            return buildCallString(procName, params);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }


    private static String tryOracle(CallableStatement stmt) {
        String className = stmt.getClass().getName();
        if (!className.startsWith("oracle.jdbc")) {
            return null;
        }
        try {
            Field sqlField = stmt.getClass().getDeclaredField("sqlObject");
            sqlField.setAccessible(true);
            Object sqlObj = sqlField.get(stmt);
            if (sqlObj != null) {

                String sqlString = sqlObj.toString();

                return parseCallString(sqlString);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
        }
        return null;
    }


    private static String tryPostgreSQL(CallableStatement stmt) {

        String className = stmt.getClass().getName();
        if (!className.startsWith("org.postgresql.")) {
            return null;
        }
        try {
            Field f = stmt.getClass().getDeclaredField("preparedQuery");
            f.setAccessible(true);
            Object queryObj = f.get(stmt);
            if (queryObj != null) {
                String queryStr = queryObj.toString();

                return parseCallString(queryStr);
            }
        } catch (Exception e) {

        }
        return null;
    }


    private static String parseCallString(String callString) {
        if (callString == null) return null;
        Matcher m = CALL_PATTERN.matcher(callString);
        if (m.find()) {
            String procName = m.group(1).trim();
            String params = m.group(2).trim();
            return buildCallString(procName, params);
        }

        return callString;
    }


    private static String parseByToString(CallableStatement stmt) {
        String ts = stmt.toString();
        return parseCallString(ts);
    }

    private static String buildCallString(String procName, String params) {

        if (params == null || params.isEmpty()) {
            return "CALL " + procName;
        }
        return "CALL " + procName + "(" + params + ")";
    }
}
