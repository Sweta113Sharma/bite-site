package com.bitesite.dao;

import com.bitesite.dto.AdminInsights;
import com.bitesite.dto.PlatformSnapshot;

/** Aggregates for the admin home screen. Read-only and deliberately small. */
public interface DashboardDao {

    PlatformSnapshot platformSnapshot();

    /** The insight blocks (sell-outs, popular items, peak hours) for today. */
    AdminInsights insights();
}
