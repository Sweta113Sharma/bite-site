package com.bitesite.dao;

import com.bitesite.dto.PlatformSnapshot;

/** Aggregates for the admin home screen. Read-only and deliberately small. */
public interface DashboardDao {

    PlatformSnapshot platformSnapshot();
}
