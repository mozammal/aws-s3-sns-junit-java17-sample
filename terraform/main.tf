terraform {
  required_version = ">= 1.0.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

locals {
  object_source = "../${path.module}/${var.zip_path}"
}

resource "aws_s3_object" "file_upload" {
  bucket      = var.aws_bucket_name_for_lambda_zip
  key         = var.zip_path
  source      = local.object_source
  source_hash = filemd5(local.object_source)
}

data "aws_s3_bucket" "folder_to_upload_test_data_file" {
  bucket = var.folder_to_upload_test_data_file
}

resource "aws_sns_topic" "fan_out_topic" {
  name = "demo-topic"
}

resource "aws_iam_role" "event_producer_role" {
  assume_role_policy = jsonencode({
    Version   = "2012-10-17",
    Statement = [
      {
        Action    = "sts:AssumeRole",
        Effect    = "Allow",
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_policy" "lambda_cloudwatch_policy" {
  name = "lambda_cloudwatch_policy"

  policy = jsonencode({
    Version   = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ],
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_attach_cloudwatch_policy" {
  role       = aws_iam_role.event_producer_role.name
  policy_arn = aws_iam_policy.lambda_cloudwatch_policy.arn
}

resource "aws_iam_policy" "s3_read_policy" {
  name        = "S3ReadPolicy"
  description = "Policy to allow read access to S3 bucket"
  policy      = jsonencode({
    Version   = "2012-10-17",
    Statement = [
      {
        Action   = ["s3:GetObject"],
        Effect   = "Allow",
        Resource = ["arn:aws:s3:::${data.aws_s3_bucket.folder_to_upload_test_data_file.id}/*"]
      }
    ]
  })
}

resource "aws_iam_policy" "sns_publish_policy" {
  name        = "SNSPublishPolicy"
  description = "Policy to allow publishing to SNS topic"
  policy      = jsonencode({
    Version   = "2012-10-17",
    Statement = [
      {
        Action   = ["sns:Publish"],
        Effect   = "Allow",
        Resource = aws_sns_topic.fan_out_topic.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "s3_read_policy_attachment" {
  role       = aws_iam_role.event_producer_role.name
  policy_arn = aws_iam_policy.s3_read_policy.arn
}

resource "aws_iam_role_policy_attachment" "sns_publish_policy_attachment" {
  role       = aws_iam_role.event_producer_role.name
  policy_arn = aws_iam_policy.sns_publish_policy.arn
}

resource "aws_lambda_function" "event_producer" {
  function_name = "event_producer_lambda"
  s3_bucket     = aws_s3_object.file_upload.bucket
  s3_key        = aws_s3_object.file_upload.key
  handler       = "org.example.handler.EventProducer::handleRequest"
  runtime       = "java17"
  role          = aws_iam_role.event_producer_role.arn
  memory_size   = 512
  timeout       = 10

  environment {
    variables = {
      FAN_OUT_TOPIC = aws_sns_topic.fan_out_topic.arn
    }
  }
}

resource "aws_lambda_permission" "allow_s3_to_invoke_event_producer" {
  statement_id  = "AllowExecutionFromS3"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.event_producer.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = data.aws_s3_bucket.folder_to_upload_test_data_file.arn
}

resource "aws_s3_bucket_notification" "s3_event" {
  bucket = data.aws_s3_bucket.folder_to_upload_test_data_file.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.event_producer.arn
    events              = ["s3:ObjectCreated:*"]
  }
  depends_on = [aws_lambda_permission.allow_s3_to_invoke_event_producer]
}

resource "aws_lambda_permission" "s3_event_permission" {
  statement_id  = "AllowS3InvokeLambda"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.event_producer.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = data.aws_s3_bucket.folder_to_upload_test_data_file.arn
}

resource "aws_lambda_function" "event_consumer" {
  function_name = "event_consumer_lambda"
  s3_bucket     = aws_s3_object.file_upload.bucket
  s3_key        = aws_s3_object.file_upload.key
  handler       = "org.example.handler.EventConsumer::handleRequest"
  runtime       = "java17"
  role          = aws_iam_role.event_producer_role.arn
  memory_size   = 512
  timeout       = 10
}

resource "aws_sns_topic_subscription" "sns_event" {
  topic_arn = aws_sns_topic.fan_out_topic.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.event_consumer.arn
}

resource "aws_lambda_permission" "sns_event_permission_event_consumer" {
  statement_id  = "AllowSNSInvokeLambda"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.event_consumer.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.fan_out_topic.arn
}