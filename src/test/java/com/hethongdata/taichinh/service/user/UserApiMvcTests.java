package com.hethongdata.taichinh.service.user;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.config.ForecastAdminAccessConfiguration;
import com.hethongdata.taichinh.controller.exception.ApiExceptionHandler;
import com.hethongdata.taichinh.controller.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
class UserApiMvcTests {
  @Configuration @EnableWebMvc static class Config {
    @Bean UserSecurityService service() {return mock(UserSecurityService.class);}
    @Bean UserSecurityController controller(UserSecurityService s) {return new UserSecurityController(s, coverage(), overview());}
    @Bean UserMarketService market() {return mock(UserMarketService.class);}
    @Bean com.hethongdata.taichinh.controller.user.UserMarketController marketController(UserMarketService s) {return new UserMarketController(s, comparison(), coverage(), provenance(), supplementary());}
    @Bean UserComparisonService comparison(){return mock(UserComparisonService.class);}
    @Bean UserDataStatusService coverage(){return mock(UserDataStatusService.class);}
    @Bean UserOverviewService overview(){return mock(UserOverviewService.class);}
    @Bean UserSupplementaryService supplementary(){return mock(UserSupplementaryService.class);}
    @Bean UserFinancialService financial(){return mock(UserFinancialService.class);}
    @Bean UserFinancialController financialController(){return new UserFinancialController(financial(), financialView(), comparison(), coverage(), provenance());}
    @Bean UserNewsService news(){return mock(UserNewsService.class);}
    @Bean UserAnalysisService analysis(){return mock(UserAnalysisService.class);}
    @Bean UserNewsController newsController(){return new UserNewsController(news(), analysis());}
    @Bean ApiExceptionHandler errors() {return new ApiExceptionHandler();}
    @Bean UserFinancialViewService financialView() {return mock(UserFinancialViewService.class);}
    @Bean com.hethongdata.taichinh.controller.user.UserContractController contract(org.springframework.beans.factory.ObjectProvider<org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping> routes){return new com.hethongdata.taichinh.controller.user.UserContractController(routes);}
    @Bean UserLocalAiService localAi(){return mock(UserLocalAiService.class);}
    @Bean UserProvenanceService provenance(){return mock(UserProvenanceService.class);}
    @Bean com.hethongdata.taichinh.controller.user.UserLocalAiController localAiController(UserLocalAiService service){return new com.hethongdata.taichinh.controller.user.UserLocalAiController(service);}
    @Bean ForecastAdminAccessConfiguration access() {return new ForecastAdminAccessConfiguration("fixture-token-at-least-32-characters",new ObjectMapper().findAndRegisterModules());}
    @Bean Probe probe() {return new Probe();}
  }
  @Test void chartDateParametersBindAsIsoDatesAndBadRangesAreNotSilentlyCoerced() throws Exception {
    try(var context=new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();var mvc=MockMvcBuilders.webAppContextSetup(context).build();
      mvc.perform(get("/api/v1/securities/FPT/prices").param("from","2016-10-01").param("to","2026-10-01")).andExpect(status().isOk());
      verify(context.getBean(UserMarketService.class)).prices("FPT",null,"1d",java.time.LocalDate.parse("2016-10-01"),java.time.LocalDate.parse("2026-10-01"),0,250);
      mvc.perform(get("/api/v1/securities/FPT/prices").param("from","invalid")).andExpect(status().isBadRequest());
      mvc.perform(get("/api/v1/securities/FPT/financials")).andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("Missing parameter 'codes'"));
      mvc.perform(get("/api/v1/local-ai/models").param("packageId","wrong")).andExpect(status().isBadRequest());
      mvc.perform(get("/api/v1/securities/FPT/prices/9007199254740993/lineage")).andExpect(status().isOk());
      verify(context.getBean(UserProvenanceService.class)).price("FPT",null,9007199254740993L);
      mvc.perform(get("/api/v1/securities/FPT/prices/9223372036854775808/lineage")).andExpect(status().isBadRequest());
    }
  }
  @Test void consolidationPreservesTheEntirePublishedContract() throws Exception {
    try(var context=new AnnotationConfigWebApplicationContext();
        var snapshot=getClass().getResourceAsStream("/contracts/user-api.json")) {
      context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
      var mvc=MockMvcBuilders.webAppContextSetup(context).build();
      var body=mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      var mapper=new ObjectMapper();
      org.assertj.core.api.Assertions.assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(snapshot));
    }
  }
  @RestController static class Probe {@GetMapping("/api/admin/probe") String admin() {return "private";}}
  @Test void contractDescribesOnlyPublicGetRoutesAndRequiredParameters() throws Exception {
    try(var context=new AnnotationConfigWebApplicationContext()){
      context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();var mvc=MockMvcBuilders.webAppContextSetup(context).build();
      String body=mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      var json=new ObjectMapper().readTree(body);org.assertj.core.api.Assertions.assertThat(body).doesNotContain("/api/admin","fixture-token");
      org.assertj.core.api.Assertions.assertThat(json.path("paths").path("/api/v1/securities/{symbol}/financials").path("get").path("parameters").toString()).contains("codes","required\":true");
      org.assertj.core.api.Assertions.assertThat(json.path("paths").path("/api/v1/securities").has("post")).isFalse();
      var params=json.path("paths").path("/api/v1/securities/{symbol}/prices/{id}/lineage").path("get").path("parameters");
      org.assertj.core.api.Assertions.assertThat(params.toString()).contains("9223372036854775807","preserve as string");
    }
  }
  @Test void publicReadsDoNotRequireAdminTokenAndWritesAreNotAvailable() throws Exception {
    try(var context=new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
      var mvc=MockMvcBuilders.webAppContextSetup(context).build();
      mvc.perform(get("/api/v1/securities")).andExpect(status().isOk());
      mvc.perform(get("/api/admin/probe")).andExpect(status().isForbidden());
      mvc.perform(post("/api/v1/securities").contentType("application/json").content("{}"))
          .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow",org.hamcrest.Matchers.containsString("GET")));
      mvc.perform(get("/api/v1/securities").param("size","oops")).andExpect(status().isBadRequest());
      verify(context.getBean(UserSecurityService.class)).securities(null,null,true,0,25);
    }
  }
}
