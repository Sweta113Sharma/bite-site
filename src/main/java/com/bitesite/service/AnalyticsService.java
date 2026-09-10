package com.bitesite.service;

import com.bitesite.dto.analytics.AnalyticsFilter;
import com.bitesite.dto.analytics.AnalyticsReport;

public interface AnalyticsService {

    AnalyticsReport generateReport(AnalyticsFilter filter);
}
