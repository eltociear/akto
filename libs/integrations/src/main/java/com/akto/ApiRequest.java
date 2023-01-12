package com.akto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;

public class ApiRequest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient();

    public static JsonNode common(Request request) {
        Call call = client.newCall(request);
        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            ;
            return null;
        }
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            return null;
        }
        String body;
        try {
            body = responseBody.string();
        } catch (IOException e) {
            ;
            return null;
        }

        try {
            return mapper.readValue(body, JsonNode.class);
        } catch (JsonProcessingException e) {
            ;
            return null;
        }
    }

    public static JsonNode getRequest(Map<String, String> headersMap, String url) {
        Request.Builder builder = new Request.Builder().url(url);
        for (String key: headersMap.keySet()) {
            builder.addHeader(key, headersMap.get(key));
        }

        Request request = builder.build();
        return common(request);

    }

    public static JsonNode postRequest(Map<String, String> headersMap, String url, String json) {
        RequestBody body = RequestBody.create( json, MediaType.parse("application/json; charset=utf-8"));

        Request.Builder builder = new Request.Builder().post(body).url(url);
        for (String key: headersMap.keySet()) {
            builder.addHeader(key, headersMap.get(key));
        }
        Request request = builder.build();

        return common(request);
    }

    public static JsonNode putRequest(Map<String, String> headersMap, String url, String json) {
        RequestBody body = RequestBody.create( json, MediaType.parse("application/json; charset=utf-8"));

        Request.Builder builder = new Request.Builder().put(body).url(url);
        for (String key: headersMap.keySet()) {
            builder.addHeader(key, headersMap.get(key));
        }
        Request request = builder.build();

        return common(request);
    }

    public static JsonNode deleteRequest(Map<String, String> headersMap, String url) {
        Request.Builder builder = new Request.Builder().url(url).delete();
        for (String key: headersMap.keySet()) {
            builder.addHeader(key, headersMap.get(key));
        }

        
        Request request = builder.build();
        return common(request);
    }

}
