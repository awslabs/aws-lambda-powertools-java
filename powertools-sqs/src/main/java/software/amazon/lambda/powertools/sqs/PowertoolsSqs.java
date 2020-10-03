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
package software.amazon.lambda.powertools.sqs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.lambda.powertools.sqs.internal.BatchContext;
import software.amazon.lambda.powertools.sqs.internal.SqsLargeMessageAspect;
import software.amazon.payloadoffloading.PayloadS3Pointer;

import static com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import static software.amazon.lambda.powertools.sqs.internal.SqsLargeMessageAspect.processMessages;

/**
 * A class of helper functions to add additional functionality to LargeMessageHandler.
 * <p>
 * {@see PowertoolsLogging}
 */
public final class PowertoolsSqs {
    private static final Log LOG = LogFactory.getLog(PowertoolsSqs.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static SqsClient client = SqsClient.create();

    private PowertoolsSqs() {
    }

    /**
     * This is a utility method when you want to avoid using {@code LargeMessageHandler} annotation.
     * Gives you access to enriched messages from S3 in the SQS event produced via extended client lib.
     * If all the large S3 payload are successfully retrieved, it will delete them from S3 post success.
     *
     * @param sqsEvent        Event received from SQS Extended client library
     * @param messageFunction Function to execute you business logic which provides access to enriched messages from S3 when needed.
     * @return Return value from the function.
     */
    public static <R> R enrichedMessageFromS3(final SQSEvent sqsEvent,
                                              final Function<List<SQSMessage>, R> messageFunction) {
        return enrichedMessageFromS3(sqsEvent, true, messageFunction);
    }

    /**
     * This is a utility method when you want to avoid using {@code LargeMessageHandler} annotation.
     * Gives you access to enriched messages from S3 in the SQS event produced via extended client lib.
     * if all the large S3 payload are successfully retrieved, Control if it will delete payload from S3 post success.
     *
     * @param sqsEvent        Event received from SQS Extended client library
     * @param messageFunction Function to execute you business logic which provides access to enriched messages from S3 when needed.
     * @return Return value from the function.
     */
    public static <R> R enrichedMessageFromS3(final SQSEvent sqsEvent,
                                              final boolean deleteS3Payload,
                                              final Function<List<SQSMessage>, R> messageFunction) {

        List<SQSMessage> sqsMessages = sqsEvent.getRecords().stream()
                .map(PowertoolsSqs::clonedMessage)
                .collect(Collectors.toList());

        List<PayloadS3Pointer> s3Pointers = processMessages(sqsMessages);

        R returnValue = messageFunction.apply(sqsMessages);

        if (deleteS3Payload) {
            s3Pointers.forEach(SqsLargeMessageAspect::deleteMessage);
        }

        return returnValue;
    }

    public static void defaultSqsClient(SqsClient client) {
        PowertoolsSqs.client = client;
    }

    public static SqsClient defaultSqsClient() {
        return client;
    }

    public static <R> List<R> partialBatchProcessor(final SQSEvent event,
                                                    final boolean suppressException,
                                                    final Class<? extends SqsMessageHandler<R>> handler) {

        try {
            return partialBatchProcessor(event, suppressException, handler.newInstance());
        } catch (IllegalAccessException | InstantiationException e) {
            LOG.error("Failed invoking process method on handler", e);
            throw new RuntimeException("Unexpected error occurred. Please raise issue at " +
                    "https://github.com/awslabs/aws-lambda-powertools-java/issues", e);
        }
    }

    public static <R> List<R> partialBatchProcessor(final SQSEvent event,
                                                    final boolean suppressException,
                                                    final SqsMessageHandler<R> handler) {
        final List<R> handlerReturn = new ArrayList<>();

        BatchContext batchContext = new BatchContext(defaultSqsClient());

        for (SQSMessage message : event.getRecords()) {
            try {
                handlerReturn.add(handler.process(message));
                batchContext.addSuccess(message);
            } catch (Exception e) {
                batchContext.addFailure(message, e);
            }
        }

        batchContext.processSuccessAndReset(suppressException);

        return handlerReturn;
    }

    private static SQSMessage clonedMessage(final SQSMessage sqsMessage) {
        try {
            return objectMapper
                    .readValue(objectMapper.writeValueAsString(sqsMessage), SQSMessage.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
