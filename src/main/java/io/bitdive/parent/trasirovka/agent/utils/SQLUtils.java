package io.bitdive.parent.trasirovka.agent.utils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class SQLUtils {

    public static String getSQLFromStatement(Object stmt) {
        return stmt.toString();
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

