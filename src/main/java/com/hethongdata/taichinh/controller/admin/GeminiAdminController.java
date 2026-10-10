package com.hethongdata.taichinh.controller.admin;
import com.hethongdata.taichinh.service.llm.GeminiLlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/llm/gemini")
public class GeminiAdminController {
    private final org.springframework.beans.factory.ObjectProvider<GeminiLlmGateway> gateway;
    public GeminiAdminController(org.springframework.beans.factory.ObjectProvider<GeminiLlmGateway> gateway) {this.gateway=gateway;}
    @GetMapping("/configuration") public Map<String,Object> configuration() {return gateway.getObject().configuration();}
    @GetMapping("/models") public JsonNode models() throws Exception {return gateway.getObject().availableModels();}
}
