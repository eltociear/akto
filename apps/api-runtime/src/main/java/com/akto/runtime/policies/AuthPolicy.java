package com.akto.runtime.policies;

import com.akto.dto.ApiInfo;
import com.akto.dto.HttpResponseParams;
import com.akto.dto.runtime_filters.RuntimeFilter;
import com.akto.dto.type.KeyTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AuthPolicy {

    public static final String AUTHORIZATION_HEADER_NAME = "authorization";
    public static final String COOKIE_NAME = "cookie";
    private static final Logger logger = LoggerFactory.getLogger(AuthPolicy.class);

    public static boolean findAuthType(HttpResponseParams httpResponseParams, ApiInfo apiInfo, RuntimeFilter filter) {
        Set<Set<ApiInfo.AuthType>> allAuthTypesFound = apiInfo.getAllAuthTypesFound();
        if (allAuthTypesFound == null) allAuthTypesFound = new HashSet<>();

        // TODO: from custom api-token
        // NOTE: custom api-token can be in multiple headers. For example twitter api sends 2 headers access-key and access-token


        // find Authorization header
        Map<String, List<String>> headers = httpResponseParams.getRequestParams().getHeaders();
        List<String> cookieList = headers.getOrDefault(COOKIE_NAME, new ArrayList<>());
        Set<ApiInfo.AuthType> authTypes = new HashSet<>();

        // find bearer or basic tokens in any header
        for (String header : headers.keySet()) {
            List<String> headerValues = headers.getOrDefault(header, new ArrayList<>());
            for (String value : headerValues) {
                value = value.trim();
                boolean twoFields = value.split(" ").length == 2;
                if (twoFields && value.substring(0, Math.min(6,value.length())).equalsIgnoreCase("bearer")) {
                    authTypes.add(ApiInfo.AuthType.BEARER);
                } else if (twoFields && value.substring(0, Math.min(5,value.length())).equalsIgnoreCase("basic")) {
                    authTypes.add(ApiInfo.AuthType.BASIC);
                } else if (header.equals(AUTHORIZATION_HEADER_NAME) || header.equals("auth")) {
                    // todo: check jwt first and then this
                    authTypes.add(ApiInfo.AuthType.AUTHORIZATION_HEADER);
                }
            }
        }

        // find bearer or basic token in cookie values
        for (String cookieValues : cookieList) {
            String[] cookies = cookieValues.split("; ");
            for (String cookie : cookies) {
                String[] cookieFields = cookie.split("=");
                boolean twoCookieFields = cookieFields.length == 2;
                if (twoCookieFields) {
                    String value = cookieFields[1];
                    value = value.trim();
                    boolean twoFields = value.split(" ").length == 2;
                    if (twoFields && value.substring(0, Math.min(6, value.length())).equalsIgnoreCase("bearer")) {
                        authTypes.add(ApiInfo.AuthType.BEARER);
                    } else if (twoFields && value.substring(0, Math.min(5, value.length())).equalsIgnoreCase("basic")) {
                        authTypes.add(ApiInfo.AuthType.BASIC);
                    } else if (cookieFields[0].equals(AUTHORIZATION_HEADER_NAME) || cookieFields[0].equals("auth")) {
                        authTypes.add(ApiInfo.AuthType.AUTHORIZATION_HEADER);
                    }
                }
            }
        }

        // Find JWT in cookie values
        boolean flag = false;
        for (String cookieValues:cookieList){
            String[] cookies = cookieValues.split("; ");
            for (String cookie:cookies){
                String[] cookieFields = cookie.split("=");
                boolean twoFields = cookieFields.length == 2;
                if (twoFields && KeyTypes.isJWT(cookieFields[1])){
                    authTypes.add(ApiInfo.AuthType.JWT);
                    flag = true;
                    break;
                }
            }
        }

        // Find JWT in header values
        for (String headerName: headers.keySet()) {
            if (flag) break;
            if (headerName.equals(AUTHORIZATION_HEADER_NAME)){
                continue;
            }
            List<String> headerValues =headers.getOrDefault(headerName,new ArrayList<>());
            for (String header: headerValues) {
                if (KeyTypes.isJWT(header)){
                    authTypes.add(ApiInfo.AuthType.JWT);
                    flag = true;
                    break;
                }
            }
        }

        boolean returnValue = false;
        if (authTypes.isEmpty()) {
            authTypes.add(ApiInfo.AuthType.UNAUTHENTICATED);
            returnValue = true;
        }


        allAuthTypesFound.add(authTypes);
        apiInfo.setAllAuthTypesFound(allAuthTypesFound);

        return returnValue;
    }
}
