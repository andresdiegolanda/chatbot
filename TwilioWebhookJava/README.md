# Twilio Webhook Lambda with ChatGPT Integration

This project implements an AWS Lambda function (using Java 21) that acts as a Twilio WhatsApp webhook. The Lambda receives incoming messages from Twilio via API Gateway, retrieves an OpenAI API key securely from AWS Secrets Manager, calls the ChatGPT API with the message content, and returns the ChatGPT-generated response formatted as TwiML (XML) back to Twilio.

## Features

- **Webhook Endpoint:** Receives HTTP POST requests from Twilio.
- **Message Parsing:** Extracts sender and message content from URL-encoded form data.
- **Secrets Management:** Retrieves the OpenAI API key from AWS Secrets Manager.  
  **Note:** The secret is expected to be stored as JSON with the property `OpenAiApiKey`.
- **ChatGPT Integration:** Calls the ChatGPT API (using the model `gpt-3.5-turbo`) to process the message.
- **TwiML Response:** Returns the ChatGPT response within a TwiML `<Response>` XML block.
- **Built with AWS SAM:** Uses the AWS Serverless Application Model for building, testing, and deployment.

## Prerequisites

- **Java:** JDK 21 installed.
- **Maven:** Installed and configured.
- **AWS CLI:** Installed and configured with credentials.
- **AWS SAM CLI:** Installed.
- **Docker:** Installed (required for local testing with SAM CLI).
- **AWS Secrets Manager:**  
  Create a secret in AWS Secrets Manager named `OpenAiApiKey` that stores your OpenAI API key in JSON format, for example:
  ```json
  {
    "OpenAiApiKey": "your_actual_openai_api_key_here"
  }
## Project Structure

### Build
Build the project using SAM CLI:

```bash
sam build
```
This command compiles your Java code using Maven, packages your Lambda function (including dependencies), and prepares it for deployment.

### Local Testing
Create a Sample Event File:

Create a file named event.json in the project root with sample data. For example:

```json
{
  "resource": "/webhook",
  "path": "/webhook",
  "httpMethod": "post",
  "headers": {
    "Content-Type": "application/x-www-form-urlencoded"
  },
  "body": "From=%2B1234567890&Body=make%20coffee",
  "isBase64Encoded": false
}
```
Invoke the Lambda Locally:

```bash
sam local invoke "TwilioWebhookFunction" --event event.json
```
Check the output in the terminal and CloudWatch logs for debugging information.

### Deployment
Deploy your Lambda function using SAM CLI:

```bash
sam deploy --guided
```
Follow the prompts to:

- Set a stack name (e.g., TwilioWebhookStack).
- Choose your AWS region.
- Confirm IAM role creation and resource deployment.
- Save the configuration to samconfig.toml for future deployments.

After deployment, SAM CLI will provide you with the API Gateway endpoint URL for your webhook.

### Configuration Details
#### SAM Template (template.yaml):
The template configures the Lambda function with:

- **Handler:** com.adlanda.TwilioWebhookLambda::handleRequest
- **Runtime:** java21
- **CodeUri:** Points to the folder containing your code.
- **Environment Variable:** OPENAI_SECRET_ID is set to "OpenAiApiKey" (the name of your secret in Secrets Manager).
- **IAM Policy:** Grants permission to retrieve secrets via secretsmanager:GetSecretValue.

#### AWS Secrets Manager:
Store your OpenAI API key in a secret named OpenAiApiKey as JSON:

```json
{
  "OpenAiApiKey": "your_actual_openai_api_key_here"
}
```

### Troubleshooting
#### Invalid API Key Error:
If you receive a 401 error when calling ChatGPT, ensure that:

- The secret in AWS Secrets Manager is correctly configured.
- The secret is stored in JSON format with the property OpenAiApiKey.
- Your AWS CLI credentials have permission to access the secret.

#### Logging:
Use CloudWatch Logs to monitor your Lambda function for debugging and troubleshooting.

## License
This project is licensed under the Apache License 2.0. You are free to use, modify, and distribute this software under the terms of the license.

## Creator
Created by andresdiegolanda@gmail.com
