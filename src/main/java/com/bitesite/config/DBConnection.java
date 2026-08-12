package com.bitesite.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    
	private static final String URL = "jdbc:mysql://localhost:3306/bite_site_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String USER = "root"; 
    private static final String PASSWORD = "hitman"; 

    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    public static Connection getConnection() {
        Connection connection = null;
        try {
            Class.forName(DRIVER_CLASS);
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Driver not found! Check pom.xml dependencies.");
            e.printStackTrace();
        } catch (SQLException e) {
            System.err.println("❌ Connection failed! Check MySQL password or if database exists.");
            e.printStackTrace();
        }
        return connection;
    }

    public static void main(String[] args) {
        Connection conn = DBConnection.getConnection();
        if (conn != null) {
            System.out.println("✅ Success! Connected to bite_site_db successfully.");
        } else {
            System.out.println("❌ Connection failed!");
        }
    }
}