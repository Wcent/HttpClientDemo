package org.cent.HttpClientDemo.controller;

import org.apache.http.HttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * @author Vincent
 * @version 1.0 2019/11/17
 */
@RestController
public class ApiController {

    @PostMapping("/post-json")
    public Map<String, Object> postJson(@RequestBody Map<String, Object> json) {
        System.out.println(json.toString());
        return json;
    }

    @PostMapping("/post-string")
    public String postString(@RequestBody String reqbody) {
        System.out.println(reqbody);
        return reqbody;
    }
}
