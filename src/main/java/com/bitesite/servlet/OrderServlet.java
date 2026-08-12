package com.bitesite.servlet;

import com.bitesite.dao.OrderDAO;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.User;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@WebServlet("/placeOrder")
public class OrderServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private OrderDAO orderDAO = new OrderDAO();

    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        HttpSession session = request.getSession();
        User loggedUser = (User) session.getAttribute("loggedUser");

        if (loggedUser == null) {
            response.sendRedirect("login.jsp");
            return;
        }

        try {
            int itemId = Integer.parseInt(request.getParameter("itemId"));
            int quantity = Integer.parseInt(request.getParameter("quantity"));
            double price = Double.parseDouble(request.getParameter("price"));
            double subtotal = price * quantity;

            List<OrderItem> items = new ArrayList<OrderItem>();
            items.add(new OrderItem(itemId, quantity, subtotal));

            // Generate Token (e.g., TOKEN-4821)
            String tokenNo = "BITE-" + (1000 + (int)(Math.random() * 9000));

            Order newOrder = new Order(loggedUser.getUserId(), tokenNo, subtotal, "PLACED", items);
            
            boolean isPlaced = orderDAO.createOrder(newOrder);

            if (isPlaced) {
                session.setAttribute("lastToken", tokenNo);
                response.sendRedirect("order-success.jsp");
            } else {
                response.sendRedirect("student-dashboard.jsp?error=OrderFailed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.sendRedirect("student-dashboard.jsp?error=InvalidInput");
        }
    }
}