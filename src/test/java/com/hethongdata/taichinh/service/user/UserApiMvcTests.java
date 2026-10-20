package com.hethongdata.taichinh.service.user;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.config.ForecastAdminAccessConfiguration;
import com.hethongdata.taichinh.controller.exception.ApiExceptionHandler;
import com.hethongdata.taichinh.controller.user.UserSecurityController;
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
    @Bean UserSecurityController controller(UserSecurityService s) {return new UserSecurityController(s);}
    @Bean UserMarketService market() {return mock(UserMarketService.class);}
    @Bean com.hethongdata.taichinh.controller.user.UserMarketController marketController(UserMarketService s) {return new com.hethongdata.taichinh.controller.user.UserMarketController(s);}
    @Bean ApiExceptionHandler errors() {return new ApiExceptionHandler();}
    @Bean ForecastAdminAccessConfiguration access() {return new ForecastAdminAccessConfiguration("fixture-token-at-least-32-characters",new ObjectMapper().findAndRegisterModules());}
    @Bean Probe probe() {return new Probe();}
  }
  @Test void chartDateParametersBindAsIsoDatesAndBadRangesAreNotSilentlyCoerced() throws Exception {
    try(var context=new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();var mvc=MockMvcBuilders.webAppContextSetup(context).build();
      mvc.perform(get("/api/v1/securities/FPT/prices").param("from","2016-10-01").param("to","2026-10-01")).andExpect(status().isOk());
      verify(context.getBean(UserMarketService.class)).prices("FPT",null,"1d",java.time.LocalDate.parse("2016-10-01"),java.time.LocalDate.parse("2026-10-01"),0,250);
      mvc.perform(get("/api/v1/securities/FPT/prices").param("from","invalid")).andExpect(status().isBadRequest());
    }
  }
  @RestController static class Probe {@GetMapping("/api/admin/probe") String admin() {return "private";}}
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
