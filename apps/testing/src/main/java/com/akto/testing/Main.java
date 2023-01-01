package com.akto.testing;

import com.akto.DaoInit;
import com.akto.dao.AccountSettingsDao;
import com.akto.dao.context.Context;
import com.akto.dto.AccountSettings;
import com.akto.dto.testing.*;
import com.akto.dto.testing.sources.TestSourceConfig;
import com.akto.dao.testing.*;
import com.akto.dao.testing.sources.TestSourceConfigsDao;
import com.akto.log.LoggerMaker;
import com.akto.util.enums.GlobalEnums.Severity;
import com.akto.util.enums.GlobalEnums.TestCategory;
import com.mongodb.BasicDBObject;
import com.mongodb.ConnectionString;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import org.apache.commons.io.FileUtils;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Main {
    private static final LoggerMaker loggerMaker = new LoggerMaker(Main.class);

    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) throws InterruptedException {
        String mongoURI = System.getenv("AKTO_MONGO_CONN");;
        DaoInit.init(new ConnectionString(mongoURI));
        Context.accountId.set(1_000_000);

        int delta = Context.now() - 20*60;

        loggerMaker.infoAndAddToDb("Starting.......");

        AccountSettings accountSettings = AccountSettingsDao.instance.findOne(new BasicDBObject());
        boolean runStatusCodeAnalyser = accountSettings == null ||
                accountSettings.getSetupType() != AccountSettings.SetupType.PROD;

        if (runStatusCodeAnalyser) {
            StatusCodeAnalyser.run();
        }

        TestExecutor testExecutor = new TestExecutor();

        while (true) {
            int start = Context.now();

            Bson filter1 = Filters.and(
                    Filters.eq(TestingRun.STATE, TestingRun.State.SCHEDULED),
                    Filters.lte(TestingRun.SCHEDULE_TIMESTAMP, Context.now())
            );
            Bson filter2 = Filters.and(
                    Filters.eq(TestingRun.STATE, TestingRun.State.RUNNING),
                    Filters.lte(TestingRun.SCHEDULE_TIMESTAMP, delta)
            );

            Bson update = Updates.combine(
                    Updates.set(TestingRun.PICKED_UP_TIMESTAMP, Context.now()),
                    Updates.set(TestingRun.STATE, TestingRun.State.RUNNING)
            );

            TestingRun testingRun = TestingRunDao.instance.getMCollection().findOneAndUpdate(
                    Filters.or(filter1,filter2), update
            );

            // TODO: find a better solution than wait
            if (testingRun == null) {
                try {
                    Thread.sleep(10 * 1000L);
                    continue;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            loggerMaker.infoAndAddToDb("Found one + " + testingRun.getId().toHexString());

            TestingRunResultSummary summary = new TestingRunResultSummary(start, 0, new HashMap<>(), 0, testingRun.getId(), testingRun.getId().toHexString(), 0);

            ObjectId summaryId = TestingRunResultSummariesDao.instance.insertOne(summary).getInsertedId().asObjectId().getValue();

            try {
                testExecutor.init(testingRun, summaryId);
            } catch (Exception e) {
                e.printStackTrace();
            }

            Bson completedUpdate = Updates.combine(
                    Updates.set(TestingRun.STATE, TestingRun.State.COMPLETED),
                    Updates.set(TestingRun.END_TIMESTAMP, Context.now())
            );

            if (testingRun.getPeriodInSeconds() > 0 ) {
                completedUpdate = Updates.combine(
                    Updates.set(TestingRun.STATE, TestingRun.State.SCHEDULED),
                    Updates.set(TestingRun.END_TIMESTAMP, Context.now()),
                    Updates.set(TestingRun.SCHEDULE_TIMESTAMP, testingRun.getScheduleTimestamp() + testingRun.getPeriodInSeconds())
                );                
            }

            TestingRunDao.instance.getMCollection().findOneAndUpdate(
                    Filters.eq("_id", testingRun.getId()),  completedUpdate
            );


            loggerMaker.infoAndAddToDb("Tests completed in " + (Context.now() - start) + " seconds");
        }
    }
}