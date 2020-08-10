package software.aws.lambda.logging.internal;

import java.io.InputStream;
import java.io.OutputStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

public class PowerLogToolDisabledForStream implements RequestStreamHandler {

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) {

    }
}
