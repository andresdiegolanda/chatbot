
package com.adlanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Base64;

public class TwilioWebhookLambdaTest {

    private TwilioWebhookLambda lambda;
    private Context context;

    @BeforeEach
    public void setUp() {
        lambda = new TwilioWebhookLambda();
        context = mock(Context.class);
        LambdaLogger logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    public void handleRequestShouldReturn200StatusAndXmlResponseWhenValidMessageProvided() {
        APIGatewayProxyRequestEvent requestEvent = new APIGatewayProxyRequestEvent();
        requestEvent.setBody("From=%2B1234567890&Body=Hello");
        requestEvent.setIsBase64Encoded(false);

        APIGatewayProxyResponseEvent responseEvent = lambda.handleRequest(requestEvent, context);

        assertEquals(200, responseEvent.getStatusCode());
        assertEquals("application/xml", responseEvent.getHeaders().get("Content-Type"));
    }
}
