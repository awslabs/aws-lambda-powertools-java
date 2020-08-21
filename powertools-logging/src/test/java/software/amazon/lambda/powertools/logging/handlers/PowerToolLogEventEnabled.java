package software.amazon.lambda.powertools.logging.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.lambda.powertools.logging.PowerToolsLogging;

public class PowerToolLogEventEnabled implements RequestHandler<Object, Object> {

    @PowerToolsLogging(logEvent = true)
    @Override
    public Object handleRequest(Object input, Context context) {
        return null;
    }
}
