package com.hethongdata.taichinh.controller.user;

import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The public contract follows registered user GET handlers, never the admin catalog. */
@RestController
public class UserContractController {
  /** Keep public SDK operation IDs stable when moving a handler between domain controllers. */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
  public @interface EndpointId { String value(); }
  private final ObjectProvider<RequestMappingHandlerMapping> routes;
  public UserContractController(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> routes){this.routes=routes;}
  @GetMapping("/api/v1/openapi.json") public Map<String,Object> contract(){
    var paths=new TreeMap<String,Object>();
    routes.getObject().getHandlerMethods().forEach((mapping,handler)->{
      if(!handler.getBeanType().getPackageName().equals(UserContractController.class.getPackageName())||!mapping.getMethodsCondition().getMethods().contains(RequestMethod.GET))return;
      for(String path:mapping.getPatternValues()) {
        if(!path.startsWith("/api/v1/"))continue;
        var parameters=new ArrayList<Map<String,Object>>();
        for(var parameter:handler.getMethod().getParameters()) {
          var query=parameter.getAnnotation(RequestParam.class);var variable=parameter.getAnnotation(PathVariable.class);if(query==null&&variable==null)continue;
          String name=query!=null?query.name():variable.name();if(name.isBlank())name=query!=null?query.value():variable.value();if(name.isBlank())name=parameter.getName();
          var schema=new LinkedHashMap<>(schema(parameter.getParameterizedType()));
          boolean defaulted=query!=null&&!query.defaultValue().equals(ValueConstants.DEFAULT_NONE);
          if(defaulted){String value=query.defaultValue();Object typed=value;if(parameter.getType()==int.class)typed=Integer.parseInt(value);else if(parameter.getType()==boolean.class)typed=Boolean.parseBoolean(value);schema.put("default",typed);}
          parameters.add(Map.of("name",name,"in",query==null?"path":"query","required",query==null||query.required()&&!defaulted,"schema",schema));
        }
        var responses=new LinkedHashMap<String,Object>();responses.put("200",Map.of("description","Stored public projection; business decimals and long database IDs use strings; unknown values remain null.","content",Map.of("application/json",Map.of("schema",schema(handler.getMethod().getGenericReturnType())))));
        for(String status:List.of("400","404","409","422","500"))responses.put(status,Map.of("description",switch(status){case "400"->"Invalid or missing parameter";case "404"->"Unknown or unpublished resource";case "409"->"Ambiguous listing or canonical data";case "422"->"Result exceeds a documented display limit";default->"Internal error; source details are not exposed";},"content",Map.of("application/json",Map.of("schema",Map.of("$ref","#/components/schemas/ApiError")))));
        var stableId=handler.getMethodAnnotation(EndpointId.class);
        String operationId=stableId==null?handler.getBeanType().getSimpleName()+"_"+handler.getMethod().getName():stableId.value();
        paths.put(path,Map.of("get",Map.of("operationId",operationId,"parameters",parameters,"responses",responses,"security",List.of())));
      }
    });
    var meta=Map.of("type","object","properties",Map.of("timezone",Map.of("type","string","const","Asia/Ho_Chi_Minh"),"numberEncoding",Map.of("type","string","const","DECIMAL_STRING"),"generatedAt",Map.of("type","string","format","date-time"),"notes",Map.of("type","array","items",Map.of("type","string"))));
    var error=Map.of("type","object","properties",Map.of("timestamp",Map.of("type","string","format","date-time"),"category",Map.of("type","string"),"runId",Map.of("type",List.of("string","null"),"format","uuid"),"upstreamStatus",Map.of("type",List.of("integer","null")),"message",Map.of("type","string")));
    return Map.of("openapi","3.1.0","info",Map.of("title","Financial public read API","version","2026-10-25","description","Read-only canonical data. No model execution, account writes or admin operations. Map-valued projections retain source-specific fields; consult meta.notes and the repository user API guide for semantics and bounds."),"paths",paths,"components",Map.of("schemas",Map.of("Meta",meta,"ApiError",error)),"x-timezone","Asia/Ho_Chi_Minh","x-numberEncoding","DECIMAL_STRING");
  }
  private Map<String,Object> schema(Type type){
    if(type instanceof ParameterizedType p){
      if(p.getRawType()==UserReadSupport.Data.class)return Map.of("type","object","required",List.of("data","meta"),"properties",Map.of("data",schema(p.getActualTypeArguments()[0]),"meta",Map.of("$ref","#/components/schemas/Meta")));
      if(p.getRawType()==UserReadSupport.Page.class)return Map.of("type","object","properties",Map.of("items",Map.of("type","array","items",schema(p.getActualTypeArguments()[0])),"total",Map.of("type","integer","format","int64"),"page",Map.of("type","integer"),"size",Map.of("type","integer"),"hasNext",Map.of("type","boolean"),"meta",Map.of("$ref","#/components/schemas/Meta")));
      if(p.getRawType()==List.class)return Map.of("type","array","items",schema(p.getActualTypeArguments()[0]));
      return Map.of("type","object","additionalProperties",true);
    }
    if(type==LocalDate.class)return Map.of("type","string","format","date");if(type==UUID.class)return Map.of("type","string","format","uuid");
    if(type==long.class||type==Long.class)return Map.of("type","string","pattern","^[1-9][0-9]{0,18}$","description","Positive 64-bit database ID, at most 9223372036854775807; preserve as string in JavaScript.");
    if(type==int.class||type==Integer.class)return Map.of("type","integer","format","int32");if(type==boolean.class||type==Boolean.class)return Map.of("type","boolean");
    if(type==String.class)return Map.of("type","string");return Map.of("type","object","additionalProperties",true);
  }
}
