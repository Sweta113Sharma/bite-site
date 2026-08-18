package com.bitesite.service;

import com.bitesite.dao.OutletDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Outlet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutletService {

    private final OutletDao outletDao;

    public List<Outlet> listActive(Long tenantId) {
        return outletDao.findActiveByTenantId(tenantId);
    }

    public List<Outlet> listAll(Long tenantId) {
        return outletDao.findByTenantId(tenantId);
    }

    public Outlet get(Long id, Long tenantId) {
        return outletDao.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }

    public Outlet create(Long tenantId, String name) {
        return outletDao.save(Outlet.builder().tenantId(tenantId).name(name).active(true).build());
    }
}
