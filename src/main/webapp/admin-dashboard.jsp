<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="com.bitesite.dao.MenuItemDAO, com.bitesite.model.MenuItem, com.bitesite.model.User, java.util.List" %>
<%
    User admin = (User) session.getAttribute("loggedUser");
    String role = (String) session.getAttribute("userRole");
    
    // Protect page: redirect if not logged in as Admin
    if (admin == null || !"CANTEEN_ADMIN".equalsIgnoreCase(role)) { 
        response.sendRedirect("login.jsp"); 
        return; 
    }
    
    MenuItemDAO menuDAO = new MenuItemDAO();
    List<MenuItem> menuList = menuDAO.getAvailableMenuItems();
%>
<!DOCTYPE html>
<html>
<head>
    <title>Bite Site - Admin Dashboard</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">
    <nav class="navbar navbar-dark bg-dark px-3 shadow-sm">
        <span class="navbar-brand mb-0 h1">👨‍🍳 Bite Site - Canteen Dashboard</span>
        <span class="text-white small">Logged in as: <%= admin.getName() %></span>
    </nav>

    <div class="container my-4">
        <div class="row">
            <!-- Active Menu Stock Management -->
            <div class="col-md-12">
                <div class="card border-0 shadow-sm p-3">
                    <h5 class="fw-bold mb-3">Live Canteen Menu Management</h5>
                    <table class="table table-hover align-middle">
                        <thead class="table-dark">
                            <tr>
                                <th>Item ID</th>
                                <th>Name</th>
                                <th>Category</th>
                                <th>Price</th>
                                <th>Availability</th>
                            </tr>
                        </thead>
                        <tbody>
                            <% for(MenuItem item : menuList) { %>
                                <tr>
                                    <td><%= item.getItemId() %></td>
                                    <td class="fw-bold"><%= item.getItemName() %></td>
                                    <td><span class="badge bg-secondary"><%= item.getCategory() %></span></td>
                                    <td>₹ <%= item.getPrice() %></td>
                                    <td>
                                        <span class="badge bg-success">In Stock</span>
                                    </td>
                                </tr>
                            <% } %>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    </div>
</body>
</html>