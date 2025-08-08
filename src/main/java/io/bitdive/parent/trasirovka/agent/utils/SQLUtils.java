package io.bitdive.parent.trasirovka.agent.utils;

import org.apache.commons.lang3.ObjectUtils;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLUtils {

    public static String getSQLFromStatement(Object stmt) {
        if (stmt == null) {
            return "";
        }


        if (stmt instanceof CallableStatement) {

            return getCallableStatement(stmt);
        }

        // Если ни один из вышеуказанных вариантов не сработал, применяем стандартное извлечение SQL из строки.
        return extractSQL(stmt.toString());
    }

    public static String getCallableStatement(Object stmt) {
        return CallableStatementParser.getCallableSQL((CallableStatement) stmt);
    }


    public static String extractSQL(String input) {
        Pattern pattern = Pattern.compile("(?i)\\b(select|update|insert|delete|with)\\b");
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            return input.substring(matcher.start());
        }
        return input;
    }

    public static String getConnectionUrlFromStatement(Object stmt) {
        String connectionUrl = getConnectionUrl(stmt);
        if (!ObjectUtils.isEmpty(connectionUrl)) {
            connectionUrl = connectionUrl.split(";", -1)[0];
        }
        return connectionUrl;
    }

    private static String getConnectionUrl(Object stmt) {
        try {
            Connection connection = null;
            if (stmt instanceof PreparedStatement) {
                connection = ((PreparedStatement) stmt).getConnection();
            } else if (stmt instanceof Statement) {
                connection = ((Statement) stmt).getConnection();
            }
            if (connection != null) {
                return connection.getMetaData().getURL();
            }
        } catch (Exception e) {
            System.err.println("Error extracting connection URL: " + e.getMessage());
        }
        return null;
    }
}

