package com.akto.rules;

import com.akto.dao.context.Context;
import com.akto.dto.ApiInfo;
import com.akto.dto.HttpRequestParams;
import com.akto.dto.HttpResponseParams;
import com.akto.dto.testing.AuthMechanism;
import com.akto.dto.testing.TestResult;
import com.akto.store.AuthMechanismStore;
import com.akto.store.SampleMessageStore;
import com.akto.testing.ApiExecutor;
import org.bson.types.ObjectId;

public class BOLATest extends TestPlugin {

    @Override
    public void start(ApiInfo.ApiInfoKey apiInfoKey, ObjectId testRunId) {
        System.out.println("BOLA TEST STARTING");
        HttpRequestParams httpRequestParams = SampleMessageStore.fetchHappyPath(apiInfoKey);
        if (httpRequestParams == null) {
            addWithoutRequestError(apiInfoKey, testRunId, TestResult.TestError.NO_HAPPY_PATH);
            return;
        }

        AuthMechanism authMechanism = AuthMechanismStore.getAuthMechanism();
        if (authMechanism == null) {
            addWithoutRequestError(apiInfoKey, testRunId, TestResult.TestError.NO_AUTH_MECHANISM);
            return;
        }

        authMechanism.addAuthToRequest(httpRequestParams);

        HttpResponseParams httpResponseParams = null;
        try {
            httpResponseParams = ApiExecutor.makeRequest(httpRequestParams);
            if (httpResponseParams == null) throw new Exception();
        } catch (Exception e) {
            e.printStackTrace();
            // TODO:
            return ;
        }

        boolean vulnerable = isStatusGood(httpResponseParams);

        addTestSuccessResult(httpResponseParams, testRunId, vulnerable);


    }

    @Override
    public String testName() {
        return "BOLA";
    }
}
