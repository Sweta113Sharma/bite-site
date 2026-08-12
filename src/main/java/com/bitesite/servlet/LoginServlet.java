package com.bitesite.servlet;

import com.bitesite.dao.UserDAO;
import com.bitesite.model.User;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

@WebServlet("/login")
public class LoginServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private UserDAO userDAO = new UserDAO();

    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String email = request.getParameter("email");
        String password = request.getParameter("password");
        String role = request.getParameter("role"); // "STUDENT" or "CANTEEN_ADMIN"

        HttpSession session = request.getSession();

        if ("STUDENT".equalsIgnoreCase(role)) {
            User student = userDAO.loginStudent(email, password);
            if (student != null) {
                session.setAttribute("loggedUser", student);
                session.setAttribute("userRole", "STUDENT");
                response.sendRedirect("student-dashboard.jsp");
            } else {
                request.setAttribute("errorMessage", "Invalid Student credentials!");
                request.getRequestDispatcher("login.jsp").forward(request, response);
            }
        } else if ("CANTEEN_ADMIN".equalsIgnoreCase(role)) {
            User admin = userDAO.loginAdmin(email, password);
            if (admin != null) {
                session.setAttribute("loggedUser", admin);
                session.setAttribute("userRole", "CANTEEN_ADMIN");
                response.sendRedirect("admin-dashboard.jsp");
            } else {
                request.setAttribute("errorMessage", "Invalid Admin credentials!");
                request.getRequestDispatcher("login.jsp").forward(request, response);
            }
        }
    }
}