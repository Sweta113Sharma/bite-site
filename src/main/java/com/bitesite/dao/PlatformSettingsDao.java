package com.bitesite.dao;

import java.util.Map;

/** Platform-wide key/value settings. Unlike {@link TechConfigDao}, these carry no tenant. */
public interface PlatformSettingsDao {

    /** Every setting, keyed by config_key. Missing keys are simply absent. */
    Map<String, String> findAll();

    /** Inserts or overwrites one setting. */
    void upsert(String key, String value);
}
