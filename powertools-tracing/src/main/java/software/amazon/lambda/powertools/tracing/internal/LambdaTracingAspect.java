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
package software.amazon.lambda.powertools.tracing.internal;

import java.util.function.Supplier;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import software.amazon.lambda.powertools.tracing.Tracing;

import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.coldStartDone;
import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.isColdStart;
import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.isHandlerMethod;
import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.isSamLocal;
import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.placedOnRequestHandler;
import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.placedOnStreamHandler;
import static software.amazon.lambda.powertools.core.internal.LambdaHandlerProcessor.serviceName;

@Aspect
public final class LambdaTracingAspect {
    @SuppressWarnings({"EmptyMethod"})
    @Pointcut("@annotation(tracing)")
    public void callAt(Tracing tracing) {
    }

    @Around(value = "callAt(tracing) && execution(@Tracing * *.*(..))", argNames = "pjp,tracing")
    public Object around(ProceedingJoinPoint pjp,
                         Tracing tracing) throws Throwable {
        Object[] proceedArgs = pjp.getArgs();

        Subsegment segment = AWSXRay.beginSubsegment(
            customSegmentNameOrDefault(tracing,
                () -> "## " + pjp.getSignature().getName()));
        segment.setNamespace(namespace(tracing));

        if (placedOnHandlerMethod(pjp)) {
            segment.putAnnotation("ColdStart", isColdStart());
        }

        boolean captureResponse = captureResponse(tracing);
        boolean captureError = captureError(tracing);

        try {
            Object methodReturn = pjp.proceed(proceedArgs);
            if (captureResponse) {
                segment.putMetadata(namespace(tracing), pjp.getSignature().getName() + " response", methodReturn);
            }

            coldStartDone();
            return methodReturn;
        } catch (Exception e) {
            if (captureError) {
                segment.putMetadata(namespace(tracing), pjp.getSignature().getName() + " error", e);
            }
            throw e;
        } finally {
            if (!isSamLocal()) {
                AWSXRay.endSubsegment();
            }
        }
    }

    private boolean captureResponse(Tracing powerToolsTracing) {
        switch (powerToolsTracing.captureMode()) {
            case ENVIRONMENT_VAR:
                boolean captureResponse = environmentVariable("POWERTOOLS_TRACER_CAPTURE_RESPONSE");
                return !isEnvironmentVariableSet("POWERTOOLS_TRACER_CAPTURE_RESPONSE") || captureResponse;
            case RESPONSE:
            case RESPONSE_AND_ERROR:
                return true;
            case DISABLED:
            default:
                return false;
        }
    }

    private boolean captureError(Tracing powerToolsTracing) {
        switch (powerToolsTracing.captureMode()) {
            case ENVIRONMENT_VAR:
                boolean captureError = environmentVariable("POWERTOOLS_TRACER_CAPTURE_ERROR");
                return !isEnvironmentVariableSet("POWERTOOLS_TRACER_CAPTURE_ERROR") || captureError;
            case ERROR:
            case RESPONSE_AND_ERROR:
                return true;
            case DISABLED:
            default:
                return false;
        }
    }

    private String customSegmentNameOrDefault(Tracing powerToolsTracing, Supplier<String> defaultSegmentName) {
        String segmentName = powerToolsTracing.segmentName();
        return segmentName.isEmpty() ? defaultSegmentName.get() : segmentName;
    }

    private String namespace(Tracing powerToolsTracing) {
        return powerToolsTracing.namespace().isEmpty() ? serviceName() : powerToolsTracing.namespace();
    }

    private boolean placedOnHandlerMethod(ProceedingJoinPoint pjp) {
        return isHandlerMethod(pjp)
                && (placedOnRequestHandler(pjp) || placedOnStreamHandler(pjp));
    }

    private boolean environmentVariable(String key) {
        return Boolean.parseBoolean(SystemWrapper.getenv(key));
    }

    private boolean isEnvironmentVariableSet(String key) {
        return SystemWrapper.containsKey(key);
    }
}
