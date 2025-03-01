package io.bitdive.parent.trasirovka.agent.utils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLUtils {

    public static String getSQLFromStatement(Object stmt) {
        return extractSQL(stmt.toString());
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

