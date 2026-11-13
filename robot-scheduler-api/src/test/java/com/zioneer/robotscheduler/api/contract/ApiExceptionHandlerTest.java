package com.zioneer.robotscheduler.api.contract;

import com.zioneer.robotscheduler.api.config.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 请求协议错误沿用 Spring 状态码，错误正文保持调度 detail 契约。 */
@DisplayName("全局异常处理：请求错误与服务端故障的边界")
class ApiExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RequestController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    // 浏览器地址栏访问 POST 接口应为 405，并告知允许的方法。
    @Test
    @DisplayName("GET 访问 POST 接口：返回 405，Allow 为 POST")
    void wrongMethod_preserves405AndAllowHeader() throws Exception {
        mvc.perform(get("/move"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("GET 调用仅支持 POST 的接口，应属于请求方法错误，不能返回 500")
                        .isEqualTo(405))
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.detail").isString());
    }

    // 请求内容格式错误不能被兜底成服务端故障。
    @Test
    @DisplayName("用文本提交 JSON 接口：返回 415，并提供 detail 错误说明")
    void unsupportedContentType_returns415() throws Exception {
        mvc.perform(post("/move").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").isString());
    }

    // 缺少参数继续保留已有的 400 与 detail 文案。
    @Test
    @DisplayName("缺少 robot 参数：返回 400，明确指出必填参数")
    void missingParameter_returns400() throws Exception {
        mvc.perform(get("/parameter")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("robot is required"));
    }

    // 模拟静态资源处理器抛出的同类异常，不依赖磁盘资源。
    @Test
    @DisplayName("favicon 不存在：返回 404，明确指出资源不存在")
    void faviconMissing_returns404() throws Exception {
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("resource not found: favicon.ico"));
    }

    // 真正的程序异常仍是 500，且不向客户端暴露内部错误信息。
    @Test
    @DisplayName("程序发生异常：返回 500，隐藏内部异常信息")
    void unexpectedFailure_returnsGeneric500() throws Exception {
        mvc.perform(get("/failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("internal scheduler error"));
    }

    @RestController
    static class RequestController {
        @PostMapping(value = "/move", consumes = "application/json")
        Map<String, Object> move(@RequestBody Map<String, Object> body) { return body; }

        @GetMapping("/parameter")
        String parameter(@RequestParam("robot") String robot) { return robot; }

        @GetMapping("/favicon.ico")
        void favicon() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "favicon.ico");
        }

        @GetMapping("/failure")
        void failure() { throw new IllegalStateException("private failure"); }
    }
}
