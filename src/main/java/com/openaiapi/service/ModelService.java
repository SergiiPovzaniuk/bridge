package com.openaiapi.service;

import com.openaiapi.api.dto.ModelListResponse;
import com.openaiapi.api.dto.ModelObject;
import com.openaiapi.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelService {

    private final AppProperties appProperties;
    private final UpstreamProxyService upstreamProxyService;
    private final long created = System.currentTimeMillis() / 1000;

    public ModelService(AppProperties appProperties, UpstreamProxyService upstreamProxyService) {
        this.appProperties = appProperties;
        this.upstreamProxyService = upstreamProxyService;
    }

    public ModelListResponse list() {
        if (upstreamProxyService.isEnabled()) {
            return upstreamProxyService.proxyModels();
        }
        return new ModelListResponse(appProperties.getModels().stream().map(this::toModel).toList());
    }

    public ModelObject get(String id) {
        if (upstreamProxyService.isEnabled()) {
            return upstreamProxyService.proxyModel(id);
        }
        return appProperties.getModels().stream()
                .filter(m -> m.getId().equals(id))
                .findFirst()
                .map(this::toModel)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id));
    }

    private ModelObject toModel(AppProperties.ModelConfig config) {
        ModelObject model = new ModelObject();
        model.setId(config.getId());
        model.setCreated(created);
        return model;
    }
}
