package com.hethongdata.taichinh.service.admin;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.config.ForecastAdminAccessConfiguration;
import com.hethongdata.taichinh.controller.admin.*;
import com.hethongdata.taichinh.service.market.*;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.macro.MacroJobCatalogService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Characterizes the existing ten URLs across the consolidated controllers. No DB/provider calls. */
class AdminDataRoutesTests {
    static final String TOKEN="isolated-admin-routes-token-at-least-32";
    @Configuration @EnableWebMvc static class Config {
        @Bean MarketPriceReadService prices(){return mock(MarketPriceReadService.class);}
        @Bean MarketIndexReadService indices(){return mock(MarketIndexReadService.class);}
        @Bean IndexJobProvisioningService indexJobs(){return mock(IndexJobProvisioningService.class);}
        @Bean MacroJobCatalogService macroJobs(){return mock(MacroJobCatalogService.class);}
        @Bean JdbcTemplate db(){return mock(JdbcTemplate.class);}
        @Bean HistoricalFinancialMetricService metrics(){return mock(HistoricalFinancialMetricService.class);}
        @Bean TrainingDataQualityService quality(){return mock(TrainingDataQualityService.class);}
        @Bean MarketDataAdminController market(MarketPriceReadService p,MarketIndexReadService i,IndexJobProvisioningService j,MacroJobCatalogService m,JdbcTemplate db){return new MarketDataAdminController(p,i,j,m,db);}
        @Bean FinancialDataAdminController financial(HistoricalFinancialMetricService m,TrainingDataQualityService q){return new FinancialDataAdminController(m,q,TOKEN);}
        @Bean ForecastAdminAccessConfiguration access(){return new ForecastAdminAccessConfiguration(TOKEN,new ObjectMapper().findAndRegisterModules());}
    }
    @Test void originalRoutesKeepAuthPagingStatusAndNativeDispatch() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            var prices=context.getBean(MarketPriceReadService.class);var indices=context.getBean(MarketIndexReadService.class);
            var indexJobs=context.getBean(IndexJobProvisioningService.class);var macro=context.getBean(MacroJobCatalogService.class);
            var metrics=context.getBean(HistoricalFinancialMetricService.class);var quality=context.getBean(TrainingDataQualityService.class);
            when(prices.canonicalPrices("FPT",0,100,"1d")).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(indices.indices(0,50)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(indices.canonicalPrices("VNINDEX",0,1)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(indices.memberships("VNINDEX",0,50)).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,50)));
            when(indexJobs.seed()).thenReturn(3);when(macro.seed()).thenReturn(Map.of("jobs",2));
            when(quality.coverage()).thenReturn(Map.of("fixture",true));when(quality.reviewLegacy("FPT")).thenReturn(Map.of("symbol","FPT"));
            for(String path:List.of("/market-prices/FPT","/market-indices","/market-indices/VNINDEX/prices","/market-indices/VNINDEX/memberships","/macro/coverage","/financial-data/quality/coverage"))
                mvc.perform(get("/api/admin"+path)).andExpect(status().isForbidden());
            for(String path:List.of("/market-indices/catalog/seed","/macro/jobs/seed","/financial-data/metrics/recalculate","/financial-data/quality/review-legacy/FPT"))
                mvc.perform(post("/api/admin"+path).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            verifyNoInteractions(prices,indices,indexJobs,macro,metrics,quality);
            mvc.perform(get("/api/admin/market-prices/FPT").param("page","-1").param("size","101").param("interval","1d").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/market-indices").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/market-indices/VNINDEX/prices").param("size","0").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/market-indices/VNINDEX/memberships").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/macro/coverage").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/financial-data/quality/coverage").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("fixture").value(true));
            mvc.perform(post("/api/admin/market-indices/catalog/seed").header("Authorization","Bearer "+TOKEN)).andExpect(status().isCreated()).andExpect(jsonPath("jobs").value(3));
            mvc.perform(post("/api/admin/macro/jobs/seed").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("jobs").value(2));
            mvc.perform(post("/api/admin/financial-data/quality/review-legacy/FPT").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("symbol").value("FPT"));
            mvc.perform(post("/api/admin/financial-data/metrics/recalculate").header("Authorization","Bearer "+TOKEN).contentType("application/json").content("{}")).andExpect(status().isOk());
            verify(prices).canonicalPrices("FPT",0,100,"1d");verify(indices).canonicalPrices("VNINDEX",0,1);
            verify(indices).indices(0,50);verify(indices).memberships("VNINDEX",0,50);
            verify(metrics).calculate(any());verify(quality).reviewLegacy("FPT");
        }
    }
}

