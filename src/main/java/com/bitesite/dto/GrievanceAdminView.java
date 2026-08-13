package com.bitesite.dto;

import com.bitesite.model.Grievance;

public record GrievanceAdminView(Grievance grievance, String collegeName, String raisedByName, String raisedByEmail) {
}
