/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package software.amazon.lambda.powertools.logging.internal;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor;
import software.amazon.lambda.powertools.core.internal.SystemWrapper;
import software.amazon.lambda.powertools.logging.handlers.PowerLogToolEnabled;
import software.amazon.lambda.powertools.logging.handlers.PowerLogToolEnabledForStream;
import software.amazon.lambda.powertools.logging.handlers.PowerToolDisabled;
import software.amazon.lambda.powertools.logging.handlers.PowerToolDisabledForStream;
import software.amazon.lambda.powertools.logging.handlers.PowerToolLogEventEnabled;
import software.amazon.lambda.powertools.logging.handlers.PowerToolLogEventEnabledForStream;

import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.RequestParametersEntity;
import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.ResponseElementsEntity;
import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3BucketEntity;
import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3Entity;
import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3ObjectEntity;
import static com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.UserIdentityEntity;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.reflect.FieldUtils.writeStaticField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.skyscreamer.jsonassert.JSONAssert.assertEquals;
import static software.amazon.lambda.powertools.core.internal.SystemWrapper.getenv;

class LambdaLoggingAspectTest {

    private static final int EXPECTED_CONTEXT_SIZE = 8;
    private RequestStreamHandler requestStreamHandler;
    private RequestHandler<Object, Object> requestHandler;

    @Mock
    private Context context;

    @BeforeEach
    void setUp() throws IllegalAccessException, IOException, NoSuchMethodException, InvocationTargetException {
        openMocks(this);
        ThreadContext.clearAll();
        writeStaticField(LambdaHandlerProcessor.class, "IS_COLD_START", null, true);
        setupContext();
        requestHandler = new PowerLogToolEnabled();
        requestStreamHandler = new PowerLogToolEnabledForStream();
        //Make sure file is cleaned up before running full stack logging regression
        FileChannel.open(Paths.get("target/logfile.json"), StandardOpenOption.WRITE).truncate(0).close();
        resetLogLevel(Level.INFO);
    }

    @Test
    void shouldSetLambdaContextWhenEnabled() {
        requestHandler.handleRequest(new Object(), context);

        assertThat(ThreadContext.getImmutableContext())
                .hasSize(EXPECTED_CONTEXT_SIZE)
                .containsEntry(DefaultLambdaFields.FUNCTION_ARN.getName(), "testArn")
                .containsEntry(DefaultLambdaFields.FUNCTION_MEMORY_SIZE.getName(), "10")
                .containsEntry(DefaultLambdaFields.FUNCTION_VERSION.getName(), "1")
                .containsEntry(DefaultLambdaFields.FUNCTION_NAME.getName(), "testFunction")
                .containsEntry(DefaultLambdaFields.FUNCTION_REQUEST_ID.getName(), "RequestId")
                .containsKey("coldStart")
                .containsKey("service");
    }

    @Test
    void shouldSetLambdaContextForStreamHandlerWhenEnabled() throws IOException {
        requestStreamHandler = new PowerLogToolEnabledForStream();

        requestStreamHandler.handleRequest(new ByteArrayInputStream(new byte[]{}), new ByteArrayOutputStream(), context);

        assertThat(ThreadContext.getImmutableContext())
                .hasSize(EXPECTED_CONTEXT_SIZE)
                .containsEntry(DefaultLambdaFields.FUNCTION_ARN.getName(), "testArn")
                .containsEntry(DefaultLambdaFields.FUNCTION_MEMORY_SIZE.getName(), "10")
                .containsEntry(DefaultLambdaFields.FUNCTION_VERSION.getName(), "1")
                .containsEntry(DefaultLambdaFields.FUNCTION_NAME.getName(), "testFunction")
                .containsEntry(DefaultLambdaFields.FUNCTION_REQUEST_ID.getName(), "RequestId")
                .containsKey("coldStart")
                .containsKey("service");
    }

    @Test
    void shouldSetColdStartFlag() throws IOException {
        requestStreamHandler.handleRequest(new ByteArrayInputStream(new byte[]{}), new ByteArrayOutputStream(), context);

        assertThat(ThreadContext.getImmutableContext())
                .hasSize(EXPECTED_CONTEXT_SIZE)
                .containsEntry("coldStart", "true");

        requestStreamHandler.handleRequest(new ByteArrayInputStream(new byte[]{}), new ByteArrayOutputStream(), context);

        assertThat(ThreadContext.getImmutableContext())
                .hasSize(EXPECTED_CONTEXT_SIZE)
                .containsEntry("coldStart", "false");
    }

    @Test
    void shouldNotSetLambdaContextWhenDisabled() {
        requestHandler = new PowerToolDisabled();

        requestHandler.handleRequest(new Object(), context);

        assertThat(ThreadContext.getImmutableContext())
                .isEmpty();
    }

    @Test
    void shouldNotSetLambdaContextForStreamHandlerWhenDisabled() throws IOException {
        requestStreamHandler = new PowerToolDisabledForStream();

        requestStreamHandler.handleRequest(null, null, context);

        assertThat(ThreadContext.getImmutableContext())
                .isEmpty();
    }

    @Test
    void shouldHaveNoEffectIfNotUsedOnLambdaHandler() {
        PowerLogToolEnabled handler = new PowerLogToolEnabled();

        handler.anotherMethod();

        assertThat(ThreadContext.getImmutableContext())
                .isEmpty();
    }

    @Test
    void shouldLogEventForHandler() throws IOException, JSONException {
        requestHandler = new PowerToolLogEventEnabled();
        S3EventNotification s3EventNotification = s3EventNotification();

        requestHandler.handleRequest(s3EventNotification, context);

        Map<String, Object> log = parseToMap(Files.lines(Paths.get("target/logfile.json")).collect(joining()));

        String event = (String) log.get("message");

        String expectEvent = new BufferedReader(new InputStreamReader(this.getClass().getResourceAsStream("/s3EventNotification.json")))
                .lines().collect(joining("\n"));

        assertEquals(expectEvent, event, false);
    }

    @Test
    void shouldLogEventForStreamAndLambdaStreamIsValid() throws IOException, JSONException {
        requestStreamHandler = new PowerToolLogEventEnabledForStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        S3EventNotification s3EventNotification = s3EventNotification();

        requestStreamHandler.handleRequest(new ByteArrayInputStream(new ObjectMapper().writeValueAsBytes(s3EventNotification)), output, context);

        assertThat(new String(output.toByteArray(), StandardCharsets.UTF_8))
                .isNotEmpty();

        Map<String, Object> log = parseToMap(Files.lines(Paths.get("target/logfile.json")).collect(joining()));

        String event = (String) log.get("message");

        String expectEvent = new BufferedReader(new InputStreamReader(this.getClass().getResourceAsStream("/s3EventNotification.json")))
                .lines().collect(joining("\n"));

        assertEquals(expectEvent, event, false);
    }

    @Test
    void shouldLogServiceNameWhenEnvVarSet() throws IllegalAccessException {
        writeStaticField(LambdaHandlerProcessor.class, "SERVICE_NAME", "testService", true);
        requestHandler.handleRequest(new Object(), context);

        assertThat(ThreadContext.getImmutableContext())
                .hasSize(EXPECTED_CONTEXT_SIZE)
                .containsEntry("service", "testService");
    }

    @Test
    void shouldLogxRayTraceIdEnvVarSet() {
        String xRayTraceId = "1-5759e988-bd862e3fe1be46a994272793";

        try (MockedStatic<SystemWrapper> mocked = mockStatic(SystemWrapper.class)) {
            mocked.when(() -> getenv("_X_AMZN_TRACE_ID")).thenReturn("Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1\"");

            requestHandler.handleRequest(new Object(), context);

            assertThat(ThreadContext.getImmutableContext())
                    .hasSize(EXPECTED_CONTEXT_SIZE + 1)
                    .containsEntry("xray_trace_id", xRayTraceId);
        }
    }

    private void setupContext() {
        when(context.getFunctionName()).thenReturn("testFunction");
        when(context.getInvokedFunctionArn()).thenReturn("testArn");
        when(context.getFunctionVersion()).thenReturn("1");
        when(context.getMemoryLimitInMB()).thenReturn(10);
        when(context.getAwsRequestId()).thenReturn("RequestId");
    }

    private void resetLogLevel(Level level) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method resetLogLevels = LambdaLoggingAspect.class.getDeclaredMethod("resetLogLevels", Level.class);
        resetLogLevels.setAccessible(true);
        resetLogLevels.invoke(null, level);
        writeStaticField(LambdaLoggingAspect.class, "LEVEL_AT_INITIALISATION", level, true);
    }

    private S3EventNotification s3EventNotification() {
        S3EventNotificationRecord record = new S3EventNotificationRecord("us-west-2",
                "ObjectCreated:Put",
                "aws:s3",
                null,
                "2.1",
                new RequestParametersEntity("127.0.0.1"),
                new ResponseElementsEntity("C3D13FE58DE4C810", "FMyUVURIY8/IgAtTv8xRjskZQpcIZ9KG4V5Wp6S7S/JRWeUWerMUE5JgHvANOjpD"),
                new S3Entity("testConfigRule",
                        new S3BucketEntity("mybucket",
                                new UserIdentityEntity("A3NL1KOZZKExample"),
                                "arn:aws:s3:::mybucket"),
                        new S3ObjectEntity("HappyFace.jpg",
                                1024L,
                                "d41d8cd98f00b204e9800998ecf8427e",
                                "096fKKXTRTtl3on89fVO.nfljtsv6qko",
                                "0055AED6DCD90281E5"),
                        "1.0"),
                new UserIdentityEntity("AIDAJDPLRKLG7UEXAMPLE")
        );

        return new S3EventNotification(singletonList(record));
    }

    private Map<String, Object> parseToMap(String stringAsJson) {
        try {
            return new ObjectMapper().readValue(stringAsJson, Map.class);
        } catch (JsonProcessingException e) {
            fail("Failed parsing logger line " + stringAsJson);
            return emptyMap();
        }
    }
}